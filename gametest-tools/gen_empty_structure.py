"""生成空的 GameTest 结构 NBT 文件（一次性工具）。

用法：python gen_empty_structure.py <输出相对data目录的键名> <宽> <高> <长>
例：python gen_empty_structure.py smoketests.empty3x3x3 3 3 3
输出：src/main/resources/data/bmfstown/structure/<键名>.nbt

GameTest 的结构名 = bmfstown:<批名>.<模板名>，批名 = 测试类名小写。
注意：1.21.1 的目录是**单数 structure/**（StructureTemplateManager L64）。
"""
import gzip
import io
import struct
import sys
from pathlib import Path

DATA_VERSION = 3955  # 1.21.1


def named(tag_type: int, name: str, payload_writer) -> bytes:
    out = io.BytesIO()
    out.write(struct.pack(">B", tag_type))
    encoded = name.encode("utf-8")
    out.write(struct.pack(">H", len(encoded)))
    out.write(encoded)
    payload_writer(out)
    return out.getvalue()


def write_int_array(out, values):
    out.write(struct.pack(">i", len(values)))
    for v in values:
        out.write(struct.pack(">i", v))


def write_int(out, value):
    out.write(struct.pack(">i", value))


def write_string(out, value):
    encoded = value.encode("utf-8")
    out.write(struct.pack(">H", len(encoded)))
    out.write(encoded)


def write_empty_list(out):
    out.write(struct.pack(">B", 0))  # 元素类型 End
    out.write(struct.pack(">i", 0))  # 长度 0


def write_size(out, size):
    out.write(struct.pack(">B", 3))  # TAG_List of TAG_Int
    out.write(struct.pack(">i", 3))
    for v in size:
        write_int(out, v)


def write_palette(out):
    # TAG_List of TAG_Compound：元素类型 10，长度 1，
    # 元素 = compound 负载（named 字段 + TAG_End 结尾，**没有长度前缀**）
    out.write(struct.pack(">B", 10))
    out.write(struct.pack(">i", 1))
    out.write(named(8, "Name", lambda o: write_string(o, "minecraft:air")))
    out.write(struct.pack(">B", 0))  # TAG_End 结尾


def main():
    if len(sys.argv) != 5:
        print(__doc__)
        sys.exit(1)
    key, w, h, l = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])

    fields = io.BytesIO()
    # size: List<Int>[w,h,l]
    size_payload = io.BytesIO()
    write_size(size_payload, [w, h, l])
    fields.write(named(9, "size", lambda o: o.write(size_payload.getvalue())))
    # entities: 空列表
    fields.write(named(9, "entities", write_empty_list))
    # blocks: 空列表（全 air）
    fields.write(named(9, "blocks", write_empty_list))
    # palette
    palette_payload = io.BytesIO()
    write_palette(palette_payload)
    fields.write(named(9, "palette", lambda o: o.write(palette_payload.getvalue())))
    # DataVersion
    fields.write(named(3, "DataVersion", lambda o: write_int(o, DATA_VERSION)))
    fields.write(struct.pack(">B", 0))  # TAG_End 结尾（compound 无长度前缀）

    root = io.BytesIO()
    root.write(struct.pack(">B", 10))  # 根 = TAG_Compound
    root.write(struct.pack(">H", 0))  # 根无名
    root.write(fields.getvalue())

    out_path = Path("src/main/resources/data/bmfstown/structure") / f"{key}.nbt"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(gzip.compress(root.getvalue()))
    print(f"written {out_path} ({w}x{h}x{l})")


if __name__ == "__main__":
    main()
