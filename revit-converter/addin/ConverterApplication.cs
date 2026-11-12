using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO.Pipes;
using System.Text;
using System.Text.Json;
using Autodesk.Revit.UI;

namespace H3D.RevitConverter;

public sealed class ConverterApplication : IExternalApplication
{
    private CancellationTokenSource? stop;
    private ExternalEvent? externalEvent;
    private NamedPipeServerStream? pipe;
    private StreamWriter? writer;
    private readonly object writeLock = new();
    private readonly ConcurrentQueue<(ConversionRequest Request, TaskCompletionSource Done)> requests = new();
    private string workRoot = "", sourceRoot = "";

    public Result OnStartup(UIControlledApplication application)
    {
        if (Environment.GetEnvironmentVariable("H3D_RVT_CONVERTER") != "1") return Result.Succeeded;
        var pipeName = Environment.GetEnvironmentVariable("H3D_RVT_PIPE");
        var nonce = Environment.GetEnvironmentVariable("H3D_RVT_NONCE");
        var work = Environment.GetEnvironmentVariable("H3D_RVT_WORK_ROOT");
        var source = Environment.GetEnvironmentVariable("H3D_RVT_SOURCE_ROOT");
        if (string.IsNullOrWhiteSpace(pipeName) || string.IsNullOrWhiteSpace(nonce)
            || string.IsNullOrWhiteSpace(work) || string.IsNullOrWhiteSpace(source)) return Result.Failed;
        workRoot = Path.GetFullPath(work);
        sourceRoot = Path.GetFullPath(source);
        stop = new CancellationTokenSource();
        externalEvent = ExternalEvent.Create(new RequestHandler(this));
        var version = application.ControlledApplication.VersionNumber;
        _ = Task.Run(() => ListenAsync(pipeName, nonce, version, stop.Token));
        return Result.Succeeded;
    }

    public Result OnShutdown(UIControlledApplication application)
    {
        stop?.Cancel();
        pipe?.Dispose();
        externalEvent?.Dispose();
        stop?.Dispose();
        return Result.Succeeded;
    }

    private async Task ListenAsync(string pipeName, string nonce, string version, CancellationToken token)
    {
        try
        {
            using var server = new NamedPipeServerStream(pipeName, PipeDirection.InOut, 1,
                PipeTransmissionMode.Byte, PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
            pipe = server;
            await server.WaitForConnectionAsync(token);
            using var reader = new StreamReader(server, new UTF8Encoding(false), false, 4096, true);
            using var output = new StreamWriter(server, new UTF8Encoding(false), 4096, true) { AutoFlush = true };
            lock (writeLock) writer = output;
            Send(new { type = "ready", nonce, pid = Environment.ProcessId, revitVersion = version });
            while (!token.IsCancellationRequested)
            {
                string? line = await reader.ReadLineAsync(token);
                if (line == null) break;
                ConversionRequest? request = null;
                try
                {
                    if (line.Length > 1024 * 1024) throw new InvalidDataException("转换请求过长。");
                    request = JsonSerializer.Deserialize<ConversionRequest>(line, Protocol.Json)
                        ?? throw new InvalidDataException("转换请求为空。");
                    if (request.Type != "convert") throw new InvalidDataException("不支持的转换请求。");
                    var completion = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
                    requests.Enqueue((request, completion));
                    var status = externalEvent!.Raise();
                    if (status is not ExternalEventRequest.Accepted and not ExternalEventRequest.Pending)
                    {
                        requests.TryDequeue(out _);
                        throw new InvalidOperationException($"Revit 无法接受转换事件：{status}");
                    }
                    await completion.Task.WaitAsync(token);
                }
                catch (Exception exception) when (exception is not OperationCanceledException)
                {
                    Send(new { type = "failed", fileId = request?.FileId, filePath = request?.FilePath, message = exception.Message });
                }
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception exception) { Trace.TraceError("H3D converter pipe stopped: {0}", exception.GetType().Name); }
        finally { lock (writeLock) { writer = null; pipe = null; } }
    }

    private void Send(object message)
    {
        lock (writeLock)
        {
            if (writer == null) throw new IOException("转换监督器连接已断开。");
            writer.WriteLine(JsonSerializer.Serialize(message, Protocol.Json));
        }
    }

    private void InvalidateInstance()
    {
        stop?.Cancel();
        lock (writeLock) { writer = null; pipe?.Dispose(); }
    }

    private sealed class RequestHandler(ConverterApplication owner) : IExternalEventHandler
    {
        public string GetName() => "H3D Everse RVT conversion";

        public void Execute(UIApplication application)
        {
            if (owner.stop!.IsCancellationRequested || !owner.requests.TryDequeue(out var job)) return;
            try
            {
                var request = job.Request;
                Protocol.RequireInside(owner.sourceRoot, request.FilePath);
                var workDir = Protocol.RequireInside(owner.workRoot, request.WorkDir);
                Directory.CreateDirectory(workDir);
                void Progress(decimal progress)
                {
                    owner.stop.Token.ThrowIfCancellationRequested();
                    owner.Send(new { type = "progress", request.FileId, request.FilePath, progress });
                }
                void Log(string message) => owner.Send(new { type = "log", request.FileId, request.FilePath, level = "info", message });
                string glbPath = RevitExporter.Export(application.Application, request, workDir, Progress, Log);
                owner.stop.Token.ThrowIfCancellationRequested();
                owner.Send(new { type = "extracted", request.FileId, request.FilePath, glbPath });
                job.Done.TrySetResult();
            }
            catch (RevitDocumentCloseException exception)
            {
                try { owner.Send(new { type = "failed", job.Request.FileId, job.Request.FilePath, message = exception.Message }); }
                catch (Exception transport) when (transport is IOException or ObjectDisposedException) { }
                finally
                {
                    owner.InvalidateInstance();
                    job.Done.TrySetResult();
                }
            }
            catch (Exception exception) { job.Done.TrySetException(exception); }
        }
    }
}
