"""
应用图标生成脚本
从Android XML矢量图标生成PNG图标用于桌面应用
"""

from PIL import Image, ImageDraw
import os

def create_icon(size=256):
    """创建应用图标"""

    # 创建黑色背景
    img = Image.new('RGBA', (size, size), (0, 0, 0, 255))
    draw = ImageDraw.Draw(img)

    # 缩放因子
    scale = size / 108

    # 绘制透视舞台边框
    # 前边缘（底部）
    draw.line([(25*scale, 75*scale), (83*scale, 75*scale)],
              fill=(136, 136, 136), width=int(2*scale))

    # 后边缘（顶部）
    draw.line([(38*scale, 38*scale), (70*scale, 38*scale)],
              fill=(102, 102, 102), width=int(1.5*scale))

    # 左边缘
    draw.line([(25*scale, 75*scale), (38*scale, 38*scale)],
              fill=(119, 119, 119), width=int(1.8*scale))

    # 右边缘
    draw.line([(83*scale, 75*scale), (70*scale, 38*scale)],
              fill=(119, 119, 119), width=int(1.8*scale))

    # 绘制舞者（白色球）
    draw.ellipse([(31*scale, 60*scale), (41*scale, 70*scale)],
                 fill=(255, 255, 255))

    # 绘制舞者（橙色球）
    draw.ellipse([(49.5*scale, 49.5*scale), (58.5*scale, 58.5*scale)],
                 fill=(255, 140, 0))

    # 绘制舞者（蓝色球）
    draw.ellipse([(67*scale, 60*scale), (77*scale, 70*scale)],
                 fill=(30, 144, 255))

    return img


def main():
    """主函数"""
    print("=== 舞形工坊图标生成器 ===\n")

    # 创建输出目录
    output_dir = "src/main/resources"
    os.makedirs(output_dir, exist_ok=True)

    try:
        # 生成256x256图标
        print("正在生成256x256图标...")
        icon = create_icon(256)
        icon_path = os.path.join(output_dir, "app-icon.png")
        icon.save(icon_path)
        print(f"✓ 已保存: {icon_path}")

        # 生成ICO文件（包含多种尺寸）
        print("\n正在生成ICO文件...")
        icon_sizes = [(16, 16), (32, 32), (48, 48), (256, 256)]
        icons = [create_icon(size) for size, _ in icon_sizes]

        ico_path = os.path.join(output_dir, "app-icon.ico")
        icons[0].save(ico_path, format='ICO', sizes=icon_sizes)
        print(f"✓ 已保存: {ico_path}")

        print("\n=== 图标生成完成！===")
        print("\n现在可以运行 build-exe.bat 进行打包")

    except Exception as e:
        print(f"\n[错误] 图标生成失败: {e}")
        print("\n请确保已安装 Pillow 库:")
        print("  pip install Pillow")
        return 1

    return 0


if __name__ == "__main__":
    exit(main())
