using System.Text.Json;

namespace H3D.RevitConverter;

internal sealed record ConversionOptions(bool UseDraco, bool Optimize, bool ExportProperty,
    string View, string? ViewName, string DisplayStyle, string CoordinateReference);

internal sealed record ConversionRequest(string Type, JsonElement FileId, string FilePath,
    string WorkDir, ConversionOptions Options);

internal static class Protocol
{
    internal static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    internal static string RequireInside(string root, string path)
    {
        if (!Path.IsPathFullyQualified(path)) throw new InvalidDataException("转换路径必须为绝对路径。");
        var fullRoot = Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        var fullPath = Path.GetFullPath(path);
        if (!fullPath.StartsWith(fullRoot, StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("转换路径不在专用目录中。");
        return fullPath;
    }
}
