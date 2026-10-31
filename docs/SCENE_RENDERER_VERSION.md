# 场景前端版本标记

本地试验，2026-10-31；不合并、不部署。

- `Astral3DScenes.rendererVersion` 对应 V5 新增可空列，取值 `webgl` / `webgpu`。
- 语义：创建或最后成功保存场景的前端版本。不是实际渲染 API，也不是兼容性保证。
- 原有 add/get/getAll/update 接口传递该字段，不增加新接口。
- 历史 null 不回填；缺字段的旧客户端更新沿用原有非空字段更新策略，不擅自推断版本。
- 文件更新失败且已更新数据库时，回滚须显式恢复原 null 标记；不能让 MyBatis 非空策略跳过 null。
- `tools/SceneRendererRollbackCheck.java` 通过反射和代理测试真实控制器回滚分支，不连接数据库。
  编译至临时 classes 目录后，使用构建好的 JAR：
  `java -Dloader.path=<classes目录> -Dloader.main=SceneRendererRollbackCheck -cp astral-web/target/astral-web.jar org.springframework.boot.loader.PropertiesLauncher`。
- 本轮只在 `astral_webgpu_acceptance_20261031` 运行 V5、创建验收场景并保存；没有迁移原 `astral3d_new`。
- **完整副本/备份/工作树及启动台账保存在原前端仓库**：
  `C:/project/3D/Astral3D/Astral3D/LOCAL_WEBGPU_ENVIRONMENT.md`。创建环境前先核对，避免重复复制。
- `application-dev.yml` 保留用户既有修改；不要直接裸启动测试：原配置仍可能指向原库。

手测前提：按台账启动副本服务和两个前端。两版列表应读取相同场景及标记；新建/成功保存改变对应标记，
仅查看不改变；缺失新 ZIP 的更新失败后原版本、ZIP 和标记保持；不要对历史项目执行破坏性保存测试。
