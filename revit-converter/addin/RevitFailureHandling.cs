using System.Text.Json;
using Autodesk.Revit.DB;
using Autodesk.Revit.DB.Events;
using RevitApplication = Autodesk.Revit.ApplicationServices.Application;

namespace H3D.RevitConverter;

internal sealed class RevitFailureHandling : IDisposable
{
    private readonly RevitApplication _application;
    private readonly Action<string> _log;
    internal string? Error { get; private set; }

    internal RevitFailureHandling(RevitApplication application, Action<string> log)
    {
        _application = application;
        _log = log;
        application.FailuresProcessing += Process;
    }

    private void Process(object? sender, FailuresProcessingEventArgs args)
    {
        using var accessor = args.GetFailuresAccessor();
        var rollback = false;
        foreach (var failure in accessor.GetFailureMessages())
        {
            var severity = failure.GetSeverity();
            var description = failure.GetDescriptionText();
            var warning = severity == FailureSeverity.Warning;
            _log(JsonSerializer.Serialize(new { type = "revit-failure", severity = severity.ToString(),
                definitionId = failure.GetFailureDefinitionId().Guid, description,
                action = warning ? "acknowledge-warning" : "rollback" }));
            if (warning) accessor.DeleteWarning(failure);
            else
            {
                Error ??= description;
                rollback = true;
            }
        }
        if (rollback)
        {
            // Roll back errors, never resolve them by changing the model or recommitting.
            using var options = accessor.GetFailureHandlingOptions();
            accessor.SetFailureHandlingOptions(options.SetClearAfterRollback(true));
        }
        args.SetProcessingResult(rollback ? FailureProcessingResult.ProceedWithRollBack : FailureProcessingResult.Continue);
    }

    internal void ThrowIfFailed()
    {
        if (Error is not null) throw new InvalidDataException($"Revit 转换失败：{Error}");
    }

    public void Dispose() => _application.FailuresProcessing -= Process;
}
