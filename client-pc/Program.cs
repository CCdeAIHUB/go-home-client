using System.Text.Json;

var info = new
{
    name = "Go Home PC Client",
    status = "platform shell placeholder",
    ui = "../client-ui/dist",
    jsapi = "window.GoHomeAPI"
};

Console.WriteLine(JsonSerializer.Serialize(info, new JsonSerializerOptions { WriteIndented = true }));

