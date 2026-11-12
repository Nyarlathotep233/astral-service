using System.Runtime.ExceptionServices;
using Autodesk.Revit.DB;
using Common_glTF_Exporter.Core;
using Common_glTF_Exporter.Materials;
using Common_glTF_Exporter.Windows.MainWindow;
using RevitApplication = Autodesk.Revit.ApplicationServices.Application;

namespace H3D.RevitConverter;

internal static class RevitExporter
{
    internal static string Export(RevitApplication application, ConversionRequest request, string workDir,
        Action<decimal> progress, Action<string> log)
    {
        if (request.Options.View is not "Default" and not "Name"
            || request.Options.DisplayStyle is not "Colour" and not "Realistic"
            || request.Options.CoordinateReference != "Origin")
            throw new InvalidDataException("不支持的 Everse 导出选项。");
        string outputPath = Path.Combine(workDir, "model.glb");
        if (File.Exists(outputPath)) throw new IOException("转换输出已存在，不能覆盖既有文件。");
        using var failures = new RevitFailureHandling(application, log);
        Document? document = null;
        Exception? failure = null;
        try
        {
            progress(5);
            using var info = BasicFileInfo.Extract(request.FilePath);
            if (info.Format != "2026")
                throw new InvalidDataException("当前仅支持已在 Revit 2026 中保存确认的 RVT 文件，请先人工升级检查并另存后上传。");
            string sourcePath = PrepareSource(request.FilePath, workDir, log);
            using var open = new OpenOptions();
            if (info.IsWorkshared) open.DetachFromCentralOption = DetachFromCentralOption.DetachAndPreserveWorksets;
            document = application.OpenDocumentFile(ModelPathUtils.ConvertUserVisiblePathToModelPath(sourcePath), open);
            failures.ThrowIfFailed();
            progress(15);
            View3D view = SelectView(document, request.Options);
            failures.ThrowIfFailed();
            var preferences = new Preferences
            {
                format = FormatEnum.glb,
                compression = CompressionEnum.None,
                materials = request.Options.DisplayStyle == "Realistic" ? MaterialsEnum.textures : MaterialsEnum.materials,
                normals = true,
                properties = request.Options.ExportProperty,
                relocateTo0 = false,
                flipAxis = true,
                units = UnitTypeId.Meters,
                path = Path.Combine(workDir, "model"),
                fileName = "model",
                texturePaths = TextureLocation.GetPaths(application.VersionNumber)
            };
            int total = new FilteredElementCollector(document, view.Id).WhereElementIsNotElementType().GetElementCount();
            int processed = 0, lastProgress = 15;
            void OnElement()
            {
                int value = 15 + (int)Math.Min(55, ++processed * 55L / Math.Max(1, total));
                if (value > lastProgress) { progress(value); lastProgress = value; }
            }
            log($"Everse v4.5.242; view={view.Name}; style={view.DisplayStyle}; main model; internal origin.");
            var context = new GLTFExportContext(document, view, preferences, OnElement, log);
            using (var exporter = new CustomExporter(document, context) { ShouldStopOnError = true })
                exporter.Export(view);
            failures.ThrowIfFailed();
            if (GLTFExportContext.cancelation || context.meshes.List.Count == 0 || !File.Exists(outputPath)
                || new FileInfo(outputPath).Length <= 20)
                throw new InvalidDataException("所选视图未生成可导出的主模型 GLB。");
            progress(75);
        }
        catch (Exception exception)
        {
            failure = failures.Error == null ? exception
                : new InvalidDataException($"Revit 转换失败：{failures.Error}", exception);
        }
        finally
        {
            if (document != null)
            {
                try
                {
                    if (!document.Close(false)) throw new InvalidOperationException("Revit 未能关闭源模型。");
                }
                catch (Exception closeError)
                {
                    failure = new RevitDocumentCloseException("源模型未能无保存关闭，当前 Revit 实例不可继续使用。",
                        failure == null ? closeError : new AggregateException(failure, closeError));
                }
            }
        }
        if (failure != null) ExceptionDispatchInfo.Capture(failure).Throw();
        failures.ThrowIfFailed();
        progress(80);
        return outputPath;
    }

    private static string PrepareSource(string sourcePath, string workDir, Action<string> log)
    {
        using var sourceModelPath = ModelPathUtils.ConvertUserVisiblePathToModelPath(sourcePath);
        using var transmission = TransmissionData.ReadTransmissionData(sourceModelPath);
        if (transmission == null) return sourcePath;
        var links = new List<ElementId>();
        foreach (ElementId id in transmission.GetAllExternalFileReferenceIds())
        {
            using var reference = transmission.GetLastSavedReferenceData(id);
            if (reference.ExternalFileReferenceType == ExternalFileReferenceType.RevitLink) links.Add(id);
        }
        if (links.Count == 0) return sourcePath;
        string copyPath = Path.Combine(workDir, "source.rvt");
        File.Copy(sourcePath, copyPath, false);
        foreach (ElementId id in links)
        {
            using var reference = transmission.GetLastSavedReferenceData(id);
            transmission.SetDesiredReferenceData(id, reference.GetPath(), reference.PathType, false);
        }
        transmission.IsTransmitted = true;
        using var copyModelPath = ModelPathUtils.ConvertUserVisiblePathToModelPath(copyPath);
        TransmissionData.WriteTransmissionData(copyModelPath, transmission);
        log($"Prepared task-only RVT copy with {links.Count} Revit links unloaded; uploaded source unchanged.");
        return copyPath;
    }

    private static View3D SelectView(Document document, ConversionOptions options)
    {
        using var transaction = new Transaction(document, "H3D temporary export view");
        transaction.Start();
        View3D view;
        if (options.View == "Name")
        {
            view = new FilteredElementCollector(document).OfClass(typeof(View3D)).Cast<View3D>()
                .SingleOrDefault(candidate => !candidate.IsTemplate && candidate.Name == options.ViewName)
                ?? throw new InvalidDataException($"找不到指定三维视图：{options.ViewName}");
        }
        else
        {
            ViewFamilyType type = new FilteredElementCollector(document).OfClass(typeof(ViewFamilyType)).Cast<ViewFamilyType>()
                .FirstOrDefault(candidate => candidate.ViewFamily == ViewFamily.ThreeDimensional)
                ?? throw new InvalidDataException("模型中没有标准三维视图类型。");
            view = View3D.CreateIsometric(document, type.Id);
        }
        if (transaction.Commit() != TransactionStatus.Committed)
            throw new InvalidOperationException("临时导出视图设置失败。");
        return view;
    }
}

internal sealed class RevitDocumentCloseException(string message, Exception inner) : Exception(message, inner);
