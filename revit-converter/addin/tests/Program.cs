using System.Text.Json;
using H3D.RevitConverter;

static void Check(bool condition, string description)
{
    if (!condition) throw new Exception(description);
}
static void Reject(Action action)
{
    try { action(); }
    catch (InvalidDataException) { return; }
    throw new Exception("Expected invalid path rejection.");
}
string root = Path.GetFullPath(Path.Combine(Path.GetTempPath(), "h3d-protocol-boundary"));
string child = Path.Combine(root, "task", "model.glb");
Check(Protocol.RequireInside(root, child) == child, "Child path must be preserved.");
Reject(() => Protocol.RequireInside(root, root));
Reject(() => Protocol.RequireInside(root, root + "-outside\\model.glb"));
Reject(() => Protocol.RequireInside(root, Path.Combine(root, "..", "outside.glb")));
Reject(() => Protocol.RequireInside(root, "relative.glb"));
string json = """
{"type":"convert","fileId":42,"filePath":"C:\\sources\\sample.rvt","workDir":"C:\\work\\job","options":{"useDraco":true,"optimize":false,"exportProperty":true,"view":"Default","viewName":"","displayStyle":"Colour","coordinateReference":"Origin"}}
""";
ConversionRequest request = JsonSerializer.Deserialize<ConversionRequest>(json, Protocol.Json)!;
Check(request.FileId.GetInt32() == 42 && request.Options.ExportProperty && request.Options.View == "Default", "Protocol request fields.");
Check(JsonSerializer.Serialize(new { request.FileId, request.FilePath }, Protocol.Json).Contains("\"fileId\":42"), "Numeric task ID must remain numeric.");
Console.WriteLine("C# protocol checks passed (no Revit / filesystem writes).");
