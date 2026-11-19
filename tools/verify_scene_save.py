"""Local MySQL/API save checks. Run exercise, restart the backend, then replay.

Reads .git/codex-runtime/application.properties or H3D_SAVE_TEST_CONFIG.
Accepts localhost astral3d_dev and isolated astral3d_dev_cr_* test databases.
Creates one uniquely identified fixture and a trigger scoped to that fixture.
The replay phase removes the fixture and its uploads after checking persistence.
"""
import argparse
import concurrent.futures
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CONFIG = Path(os.environ.get("H3D_SAVE_TEST_CONFIG", ROOT / ".git/codex-runtime/application.properties"))
STATE = CONFIG.parent / "scene-save-check.json"
properties = dict(line.split("=", 1) for line in CONFIG.read_text().splitlines() if "=" in line)
BASE = "http://127.0.0.1:" + str(int(properties.get("server.port", 8080))) + "/api"
database = re.fullmatch(r"jdbc:mysql://127\.0\.0\.1:3306/(astral3d_dev(?:_cr_[a-f0-9]+)?)\?.*", properties["spring.datasource.url"])
assert database, "Only an isolated local development database is allowed"
UPLOAD = Path(properties["astral.uploadDir"]).resolve()


def sql(query):
    env = dict(os.environ, MYSQL_PWD=properties["spring.datasource.password"])
    command = ["mysql", "--protocol=TCP", "--host=127.0.0.1", "--port=3306", "--user=" + properties["spring.datasource.username"],
               "--database=" + database.group(1), "--batch", "--skip-column-names", "--execute=" + query]
    result = subprocess.run(command, env=env, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError("Local validation SQL failed: " + result.stderr)
    return result.stdout.strip()


def api(path, body=None, method=None):
    request = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(),
                                     method=method or ("GET" if body is None else "POST"), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def upload_result(biz, filename, content):
    boundary = "H3D" + uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"biz\"\r\n\r\n{biz}\r\n"
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\n"
            "Content-Type: application/octet-stream\r\n\r\n").encode() + content + f"\r\n--{boundary}--\r\n".encode()
    request = urllib.request.Request(BASE + "/common/upload", data=body, headers={"Content-Type": "multipart/form-data; boundary=" + boundary})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def upload(biz, filename, content):
    result = upload_result(biz, filename, content)
    assert result["code"] == 200, result
    return result["result"]


def payload(scene_id, expected, operation):
    suffix = f"{scene_id}-V{expected + 1}-{operation}-{uuid.uuid4().hex}"
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w") as target:
        target.writestr("verification.txt", operation)
    scene = {"sceneName": "H3D save validation", "hasDrawing": 0,
             "zip": upload("upload/3DEditor/scene/" + suffix, "scene.zip", archive.getvalue()),
             "coverPicture": upload("upload/3DEditor/screenshot/" + suffix, "cover.png", b"verification-cover"), "zipSize": "1 KB"}
    return {"scene": scene, "expectedVersion": expected, "operationId": operation}


def current(scene_id):
    result = api("/editor3d/scenes/get/" + scene_id)
    assert result["code"] == 200, result
    return result["result"]


def cleanup(scene_id):
    assert re.fullmatch(r"[a-f0-9]{32}", scene_id)
    sql(f"DELETE FROM h3d_legacy_scene_upload WHERE scene_id='{scene_id}'; DELETE FROM h3d_scene_save_operation WHERE scene_id='{scene_id}'; DELETE FROM astral_3d_scenes WHERE id='{scene_id}';")
    # Verify every resolved deletion target is inside this fixture's upload roots.
    for subdir in ["upload/3DEditor/scene", "upload/3DEditor/screenshot", ".scene-save-backup"]:
        parent = (UPLOAD / subdir).resolve()
        assert parent.is_relative_to(UPLOAD)
        pattern = scene_id if subdir == ".scene-save-backup" else scene_id + "-V*"
        for path in parent.glob(pattern):
            resolved = path.resolve()
            assert resolved.is_relative_to(parent) and resolved != parent
            assert resolved.name.startswith(scene_id)
            shutil.rmtree(resolved)


def exercise():
    assert not STATE.exists(), "Replay/clean the previous fixture first"
    scene_id = uuid.uuid4().hex
    trigger = "h3d_verify_" + scene_id[:16]
    keep = False
    try:
        created = api("/editor3d/scenes/add", {"id": scene_id, "sceneName": "H3D save validation", "sceneVersion": 0, "projectType": 0, "hasDrawing": 0, "coverPicture": "", "zip": "", "zipSize": "0 KB"})
        assert created["code"] == 200, created
        endpoint = "/editor3d/scenes/save/" + scene_id
        first = payload(scene_id, 0, str(uuid.uuid4()))
        saved = api(endpoint, first)
        assert saved["code"] == 200 and saved["result"]["scene"]["sceneVersion"] == 1, saved
        duplicate = api(endpoint, first)
        assert duplicate["code"] == 200 and duplicate["result"]["replayed"], duplicate
        assert current(scene_id)["sceneVersion"] == 1
        reused = api(endpoint, dict(first, expectedVersion=1))
        assert reused["code"] == 409 and reused["result"]["reason"] == "OPERATION_ID_REUSED", reused

        stale = payload(scene_id, 0, "stale")
        conflict = api(endpoint, stale)
        assert conflict["code"] == 409 and conflict["result"]["reason"] == "VERSION_CONFLICT", conflict

        contenders = [payload(scene_id, 1, "race-a"), payload(scene_id, 1, "race-b")]
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            outcomes = list(pool.map(lambda item: api(endpoint, item), contenders))
        assert sorted(item["code"] for item in outcomes) == [200, 409], outcomes
        assert current(scene_id)["sceneVersion"] == 2

        retries = [payload(scene_id, 2, "same-op"), payload(scene_id, 2, "same-op")]
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            outcomes = list(pool.map(lambda item: api(endpoint, item), retries))
        assert all(item["code"] == 200 for item in outcomes), outcomes
        assert sorted(item["result"]["replayed"] for item in outcomes) == [False, True], outcomes
        assert outcomes[0]["result"]["scene"]["zip"] == outcomes[1]["result"]["scene"]["zip"]
        before_failure = current(scene_id)
        assert before_failure["sceneVersion"] == 3
        before_bytes = (UPLOAD / before_failure["zip"]).read_bytes()

        failed = payload(scene_id, 3, "ledger-fail")
        sql(f"DELIMITER $$\nCREATE TRIGGER {trigger} BEFORE INSERT ON h3d_scene_save_operation FOR EACH ROW BEGIN IF NEW.scene_id='{scene_id}' AND NEW.operation_id='ledger-fail' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected validation ledger failure'; END IF; END$$\nDELIMITER ;")
        try:
            rejected = api(endpoint, failed)
            assert rejected["code"] == 500 and rejected["result"]["reason"] == "SAVE_FAILED_ROLLED_BACK", rejected
            assert current(scene_id)["sceneVersion"] == 3
            assert current(scene_id)["zip"] == before_failure["zip"]
            assert (UPLOAD / before_failure["zip"]).read_bytes() == before_bytes
            assert api(endpoint + "/operations/ledger-fail")["result"] is None
        finally:
            sql("DROP TRIGGER IF EXISTS " + trigger)

        missing = {"expectedVersion": 3, "operationId": "missing", "scene": {"zip": "missing.zip"}}
        assert api(endpoint, missing)["code"] == 400
        assert current(scene_id)["sceneVersion"] == 3

        legacy = payload(scene_id, 3, "legacy-check")["scene"]
        legacy["sceneVersion"] = 4
        legacy_result = api("/editor3d/scenes/update/" + scene_id, legacy, "PUT")
        assert legacy_result["code"] == 200 and current(scene_id)["sceneVersion"] == 4, legacy_result
        assert api(endpoint, first)["result"]["scene"]["sceneVersion"] == 1
        assert current(scene_id)["sceneVersion"] == 4, "A receipt must never revert the current scene"
        # Two old editors upload fixed names *before* PUT. Check both an early
        # collision and a loser trying to overwrite an already committed group.
        directory = f"upload/3DEditor/scene/{scene_id}-V5"
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            contents = [b"first editor", b"second editor"]
            uploads = list(pool.map(lambda data: upload_result(directory, "scene.zip", data), contents))
        assert sorted(result["code"] for result in uploads) == [200, 409], uploads
        winner = next(index for index, result in enumerate(uploads) if result["code"] == 200)
        first_zip = uploads[winner]["result"]
        assert upload(directory, "scene.zip", contents[winner]) == first_zip
        group = upload(directory, "group.zip", b"winner group")
        cover = upload(f"upload/3DEditor/screenshot/{scene_id}-V5", "cover.png", b"winner cover")
        committed = api("/editor3d/scenes/update/" + scene_id, {"sceneVersion": 5, "zip": first_zip, "coverPicture": cover}, "PUT")
        assert committed["code"] == 200, committed
        assert upload_result(directory, "group.zip", b"late losing group")["code"] == 409
        assert (UPLOAD / first_zip).read_bytes() == contents[winner] and (UPLOAD / group).read_bytes() == b"winner group"
        renamed = api("/editor3d/scenes/update/" + scene_id, {"sceneName": "metadata only"}, "PUT")
        assert renamed["code"] == 200 and current(scene_id)["sceneVersion"] == 6, renamed
        kept = api("/editor3d/scenes/update/" + scene_id, {"sceneName": "explicit target", "sceneVersion": 7, "zip": first_zip}, "PUT")
        assert kept["code"] == 200 and current(scene_id)["coverPicture"] == cover, kept
        assert current(scene_id)["zip"] == first_zip and (UPLOAD / group).read_bytes() == b"winner group"
        STATE.write_text(json.dumps({"sceneId": scene_id, "request": retries[0], "expectedCurrentVersion": 7}, indent=2))
        keep = True
        print(json.dumps({"phase": "exercise", "passed": ["UUID-upload-path", "save", "duplicate", "operation-id-reused", "stale-version", "two-writers-CAS", "concurrent-same-operation", "ledger-failure-rollback", "missing-assets", "legacy-PUT", "historical-replay", "legacy-upload-race", "legacy-late-upload", "metadata-only-PUT"], "sceneId": scene_id}))
    finally:
        sql("DROP TRIGGER IF EXISTS " + trigger)
        if not keep:
            cleanup(scene_id)


def replay():
    state = json.loads(STATE.read_text())
    scene_id = state["sceneId"]
    try:
        result = api("/editor3d/scenes/save/" + scene_id, state["request"])
        assert result["code"] == 200 and result["result"]["replayed"], result
        assert result["result"]["scene"]["sceneVersion"] == 3
        assert current(scene_id)["sceneVersion"] == state["expectedCurrentVersion"]
        print(json.dumps({"phase": "replay-after-restart", "passed": True, "currentVersion": state["expectedCurrentVersion"]}))
    finally:
        cleanup(scene_id)
        STATE.unlink()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("phase", choices=["exercise", "replay"])
    args = parser.parse_args()
    exercise() if args.phase == "exercise" else replay()
