using System;
using System.Drawing;
using System.IO;
using System.Windows.Forms;
using D31FlashTool;

internal static class RescueLayoutTests
{
    private static int buttons;
    private static void Walk(Control parent)
    {
        parent.PerformLayout();
        foreach (Control child in parent.Controls)
        {
            if (child is Button)
            {
                buttons++;
                if (child.Right > parent.ClientSize.Width || child.Bottom > parent.ClientSize.Height)
                    throw new Exception("按钮超出容器：" + child.Text);
                Size text = TextRenderer.MeasureText(child.Text, child.Font);
                if (text.Width + (child is Button && ((Button)child).Image != null ? ((Button)child).Image.Width : 0) > child.Width)
                    throw new Exception("按钮文字或图标无法容纳：" + child.Text);
                if (child.Text.Contains("卡刷") && child.Enabled) throw new Exception("卡刷入口不能启用");
            }
            Walk(child);
        }
    }
    [STAThread]
    private static int Main(string[] args)
    {
        try
        {
            Application.EnableVisualStyles();
            using (var form = new RescueForm(args[0], "192.168.1.1"))
            {
                form.ShowInTaskbar = false;
                form.Show();
                Application.DoEvents();
                Walk(form);
                using (var bitmap = new Bitmap(form.Width, form.Height))
                {
                    form.DrawToBitmap(bitmap, new Rectangle(Point.Empty, form.Size));
                    bitmap.Save(args[1] + ".png", System.Drawing.Imaging.ImageFormat.Png);
                }
                form.Size = form.MinimumSize;
                Walk(form);
            }
            File.WriteAllText(args[1], "通过：急救窗口默认及最小尺寸，共检查" + buttons + "个按钮布局；卡刷入口禁用。未连接设备，未操作桌面窗口。");
            return 0;
        }
        catch (Exception ex) { File.WriteAllText(args[1], ex.ToString()); return 1; }
    }
}
