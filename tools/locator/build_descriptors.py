#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
OKK 定位描述符生成器（构建期专用）

作用：在**构建期**用字符串锚点找到微信混淆类/方法，把它们固化成
"Lcom/xxx;->method(Sig)V" 形式的描述符写入 assets/descriptors.bin。
运行时的模块只读取这个二进制描述符表，不再携带、解码或搜索任何
日志字符串锚点， thereby 避开"字符串特征"。

依赖：仅 Python 标准库（3.8+）。直接解析 APK 内的 classes*.dex。

用法：
  python build_descriptors.py \
      --apk "E:/文件/O+Share/微信.apk" \
      --spec anchors.json \
      --out  core/src/main/assets/descriptors.bin \
      --json core/src/main/assets/descriptors.json
"""

from __future__ import annotations

import argparse
import json
import struct
import sys
import zlib
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Dict, Iterable, Iterator, List, Optional, Sequence, Set, Tuple

MAGIC = b"OKKD"
FORMAT_VERSION = 1
DEX_MAGIC = (b"dex\n035\x00", b"dex\n037\x00", b"dex\n039\x00")


# ---------------------------------------------------------------------------
# MUTF-8
# ---------------------------------------------------------------------------
def decode_mutf8(data: bytes) -> str:
    """DEX 使用 Modified UTF-8：NUL 编码为 C0 80，增补字符为代理对。"""
    out: List[str] = []
    i = 0
    n = len(data)
    while i < n:
        b = data[i]
        if b < 0x80:
            if b == 0:
                raise ValueError("raw NUL in MUTF-8")
            out.append(chr(b))
            i += 1
        elif b & 0xE0 == 0xC0:
            if i + 1 >= n:
                raise ValueError("truncated MUTF-8")
            cp = ((b & 0x1F) << 6) | (data[i + 1] & 0x3F)
            out.append(chr(cp))
            i += 2
        elif b & 0xF0 == 0xE0:
            if i + 2 >= n:
                raise ValueError("truncated MUTF-8")
            cp = ((b & 0x0F) << 12) | ((data[i + 1] & 0x3F) << 6) | (data[i + 2] & 0x3F)
            i += 3
            # 合并 UTF-16 代理对为单个码点
            if 0xD800 <= cp <= 0xDBFF and i + 3 <= n:
                b2 = data[i]
                if b2 & 0xF0 == 0xE0:
                    lo = (
                        ((b2 & 0x0F) << 12)
                        | ((data[i + 1] & 0x3F) << 6)
                        | (data[i + 2] & 0x3F)
                    )
                    if 0xDC00 <= lo <= 0xDFFF:
                        cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00)
                        i += 3
            out.append(chr(cp))
        else:
            raise ValueError(f"invalid MUTF-8 lead byte 0x{b:02x}")
    return "".join(out)


def uleb128(data: bytes, pos: int) -> Tuple[int, int]:
    result = 0
    shift = 0
    while True:
        if pos >= len(data):
            raise ValueError("truncated uleb128")
        b = data[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if b < 0x80:
            return result, pos
        shift += 7
        if shift > 35:
            raise ValueError("uleb128 too long")


# ---------------------------------------------------------------------------
# DEX 模型
# ---------------------------------------------------------------------------
@dataclass(frozen=True)
class MethodInfo:
    class_desc: str
    name: str
    signature: str
    access_flags: int
    code: bytes  # 指令单元流（每个指令 2 字节，小端）

    @property
    def descriptor(self) -> str:
        return f"{self.class_desc}->{self.name}{self.signature}"

    @property
    def code_hash(self) -> int:
        return zlib.crc32(self.code) & 0xFFFFFFFF


class DexFile:
    def __init__(self, raw: bytes, label: str = ""):
        self.raw = raw
        self.label = label
        if raw[:8] not in DEX_MAGIC:
            raise ValueError(f"not a dex file: {raw[:8]!r}")
        # 8 字节 magic + 4 字节 checksum + 20 字节 SHA1 签名 = 32
        h = struct.unpack_from("<20I", raw, 32)
        (
            self.file_size,
            self.header_size,
            self.endian_tag,
            _link_size, _link_off, _map_off,
            self.string_ids_size, self.string_ids_off,
            self.type_ids_size, self.type_ids_off,
            self.proto_ids_size, self.proto_ids_off,
            self.field_ids_size, self.field_ids_off,
            self.method_ids_size, self.method_ids_off,
            self.class_defs_size, self.class_defs_off,
            _data_size, _data_off,
        ) = h
        if self.endian_tag != 0x12345678:
            raise ValueError("big-endian dex unsupported")
        self._string_cache: Dict[int, str] = {}
        self._type_cache: Dict[int, str] = {}
        self._proto_cache: Dict[int, Tuple[str, str]] = {}
        self._methods: Optional[List[MethodInfo]] = None

    # ---- 基础表 ----
    def string(self, idx: int) -> str:
        if idx in self._string_cache:
            return self._string_cache[idx]
        off = struct.unpack_from("<I", self.raw, self.string_ids_off + idx * 4)[0]
        # utf16_size 是 UTF-16 码元数，不是字节数；MUTF-8 数据以 0x00 结尾
        _utf16_size, pos = uleb128(self.raw, off)
        end = self.raw.index(0, pos)
        value = decode_mutf8(self.raw[pos:end])
        self._string_cache[idx] = value
        return value

    def type_desc(self, idx: int) -> str:
        if idx == 0xFFFFFFFF:
            return ""
        if idx in self._type_cache:
            return self._type_cache[idx]
        string_idx = struct.unpack_from("<I", self.raw, self.type_ids_off + idx * 4)[0]
        value = self.string(string_idx)
        self._type_cache[idx] = value
        return value

    def proto(self, idx: int) -> Tuple[str, str]:
        """返回 (参数签名串, 返回类型签名)。"""
        if idx in self._proto_cache:
            return self._proto_cache[idx]
        base = self.proto_ids_off + idx * 12
        _shorty_idx, return_idx, params_off = struct.unpack_from("<III", self.raw, base)
        ret = self.type_desc(return_idx)
        params: List[str] = []
        if params_off:
            count = struct.unpack_from("<I", self.raw, params_off)[0]
            pos = params_off + 4
            for _ in range(count):
                t_idx = struct.unpack_from("<H", self.raw, pos)[0]
                pos += 2
                params.append(self.type_desc(t_idx))
        sig = "(" + "".join(params) + ")" + ret
        self._proto_cache[idx] = (sig, ret)
        return sig, ret

    def method_ref(self, method_idx: int) -> Tuple[str, str, str]:
        base = self.method_ids_off + method_idx * 8
        class_idx, proto_idx, name_idx = struct.unpack_from("<HHI", self.raw, base)
        sig, _ = self.proto(proto_idx)
        return self.type_desc(class_idx), self.string(name_idx), sig

    # ---- 指令 / 类数据 ----
    @staticmethod
    def const_string_indices(code: bytes) -> Set[int]:
        """扫描 const-string / const-string/jumbo 引用的 string_id。"""
        found: Set[int] = set()
        i = 0
        n = len(code) - 1
        while i < n:
            if i + 5 > len(code):
                break
            low = code[i]
            op = low & 0xFF
            if op == 0x1A:  # const-string vAA, string@BBBB
                idx = code[i + 1] | (code[i + 2] << 8)
                found.add(idx)
                i += 4
            elif op == 0x1B:  # const-string/jumbo vAA, string@BBBBBBBB
                idx = (
                    code[i + 1]
                    | (code[i + 2] << 8)
                    | (code[i + 3] << 16)
                    | (code[i + 4] << 24)
                )
                found.add(idx)
                i += 6
            else:
                i += 2
        return found

    def static_value_strings(self) -> Dict[int, Set[str]]:
        """返回 string_id -> 引用它的类集合（来自 static_values_off 编码数组）。

        微信部分锚点字符串只出现在静态字段初始化里，方法体通过字段间接使用，
        const-string 扫描会漏掉；这里把这类类也纳入候选。
        """
        result: Dict[int, Set[str]] = {}
        for class_def_idx in range(self.class_defs_size):
            base = self.class_defs_off + class_def_idx * 32
            class_idx, _access, _super, _itf, _src, _ann, _cdo, static_off = (
                struct.unpack_from("<IIIIIIII", self.raw, base)
            )
            if not static_off:
                continue
            try:
                count, pos = uleb128(self.raw, static_off)
                owner = self.type_desc(class_idx)
                for _ in range(count):
                    head = self.raw[pos]
                    pos += 1
                    value_type = head & 0x1F
                    value_arg = head >> 5
                    if value_type == 0x03:  # VALUE_STRING
                        sidx, pos = uleb128(self.raw, pos)
                        result.setdefault(sidx, set()).add(owner)
                    else:
                        pos += value_arg + 1
            except (ValueError, IndexError, struct.error):
                continue
        return result

    def methods(self) -> List[MethodInfo]:
        if self._methods is not None:
            return self._methods
        result: List[MethodInfo] = []
        for class_def_idx in range(self.class_defs_size):
            base = self.class_defs_off + class_def_idx * 32
            class_idx, _access, _super, _itf, _src, _ann, class_data_off, _static = (
                struct.unpack_from("<IIIIIIII", self.raw, base)
            )
            if not class_data_off:
                continue
            class_desc = self.type_desc(class_idx)
            pos = class_data_off
            static_fields, pos = uleb128(self.raw, pos)
            instance_fields, pos = uleb128(self.raw, pos)
            direct_methods, pos = uleb128(self.raw, pos)
            virtual_methods, pos = uleb128(self.raw, pos)
            # 跳过字段（字段编码不携带 code_off，大小固定 2 个 uleb）
            for _ in range(static_fields + instance_fields):
                _, pos = uleb128(self.raw, pos)
                _, pos = uleb128(self.raw, pos)

            method_idx = 0
            for count in (direct_methods, virtual_methods):
                for _ in range(count):
                    diff, pos = uleb128(self.raw, pos)
                    access, pos = uleb128(self.raw, pos)
                    code_off, pos = uleb128(self.raw, pos)
                    method_idx += diff
                    if not code_off or method_idx >= self.method_ids_size:
                        # 个别加固/混淆 dex 存在越界方法索引，跳过即可，不影响锚点搜索
                        continue
                    try:
                        owner, name, sig = self.method_ref(method_idx)
                        insns_size = struct.unpack_from("<I", self.raw, code_off + 12)[0]
                        insns_start = code_off + 16
                        code = self.raw[insns_start:insns_start + insns_size * 2]
                    except (ValueError, IndexError, struct.error):
                        continue
                    result.append(
                        MethodInfo(owner or class_desc, name, sig, access, code)
                    )
        self._methods = result
        return result


# ---------------------------------------------------------------------------
# 锚点 → 描述符
# ---------------------------------------------------------------------------
@dataclass
class AnchorSpec:
    key: str
    strings: List[str]
    signature_hint: Optional[str] = None
    class_hint: Optional[str] = None
    requires_all: bool = False
    note: str = ""


@dataclass
class Resolved:
    key: str
    descriptor: str
    class_name: str
    method_name: str
    signature: str
    code_hash: str
    dex: str
    note: str = ""


def load_spec(path: Path) -> List[AnchorSpec]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    items = raw["anchors"] if isinstance(raw, dict) else raw
    result: List[AnchorSpec] = []
    for item in items:
        result.append(
            AnchorSpec(
                key=item["key"],
                strings=list(item["strings"]),
                signature_hint=item.get("signature_hint"),
                class_hint=item.get("class_hint"),
                requires_all=bool(item.get("requires_all", False)),
                note=item.get("note", ""),
            )
        )
    return result


def dexes_from_apk(apk: Path) -> Iterator[Tuple[str, DexFile]]:
    import zipfile

    with zipfile.ZipFile(apk) as zf:
        for name in zf.namelist():
            if name.startswith("classes") and name.endswith(".dex"):
                yield name, DexFile(zf.read(name), name)


def resolve(anchors: Sequence[AnchorSpec], dexes: Sequence[Tuple[str, DexFile]]) -> Tuple[List[Resolved], Dict[str, List[str]]]:
    resolved: List[Resolved] = []
    ambiguous: Dict[str, List[str]] = {}

    # 每个 dex 的字符串表只解码一次，避免逐锚点重复扫描百万级字符串
    string_index: Dict[str, Dict[str, Set[int]]] = {}
    static_index: Dict[str, Dict[int, Set[str]]] = {}
    for dex_name, dex in dexes:
        index: Dict[str, Set[int]] = {}
        for idx in range(dex.string_ids_size):
            try:
                text = dex.string(idx)
            except (ValueError, IndexError):
                continue
            index.setdefault(text, set()).add(idx)
        string_index[dex_name] = index
        static_index[dex_name] = dex.static_value_strings()

    for spec in anchors:
        candidates: List[Tuple[str, MethodInfo, int]] = []
        for dex_name, dex in dexes:
            index = string_index[dex_name]
            anchor_ids: Dict[str, Set[int]] = {}
            for s in spec.strings:
                ids: Set[int] = set(index.get(s, set()))
                if len(s) >= 12:
                    for text, text_ids in index.items():
                        if s in text:
                            ids |= text_ids
                if ids:
                    anchor_ids[s] = ids
            if not anchor_ids:
                continue
            static_map = static_index[dex_name]
            for method in dex.methods():
                used = dex.const_string_indices(method.code)
                hits = 0
                for s in spec.strings:
                    ids = anchor_ids.get(s, set())
                    if not ids:
                        continue
                    if used & ids:
                        hits += 1
                        continue
                    # 静态字段初始化引用也算命中（锚点在类级别而非方法体）
                    if any(method.class_desc in static_map.get(i, set()) for i in ids):
                        hits += 1
                if spec.requires_all and hits != len(spec.strings):
                    continue
                if hits == 0:
                    continue
                candidates.append((dex_name, method, hits))

        # 精确签名/类名提示过滤
        if spec.signature_hint:
            candidates = [c for c in candidates if c[1].signature == spec.signature_hint]
        if spec.class_hint:
            candidates = [
                c for c in candidates if spec.class_hint in c[1].class_desc
            ]
        # <clinit> 往往是字符串池初始化，不是业务方法；有其它候选时优先排除
        non_clinit = [c for c in candidates if c[1].name != "<clinit>"]
        if non_clinit:
            candidates = non_clinit
        if not candidates:
            continue

        candidates.sort(key=lambda c: (-c[2], -len(c[1].code), c[1].descriptor))
        best_hits = candidates[0][2]
        best = [c for c in candidates if c[2] == best_hits]
        if len(best) > 1:
            ambiguous[spec.key] = sorted({c[1].descriptor for c in best})[:20]

        dex_name, method, _ = best[0]
        # class_desc 形如 Lcom/tencent/mm/...;
        cls = method.class_desc[1:-1] if method.class_desc.startswith("L") else method.class_desc
        resolved.append(
            Resolved(
                key=spec.key,
                descriptor=method.descriptor,
                class_name=cls,
                method_name=method.name,
                signature=method.signature,
                code_hash=f"{method.code_hash:08x}",
                dex=dex_name,
                note=spec.note,
            )
        )
    return resolved, ambiguous


# ---------------------------------------------------------------------------
# 输出
# ---------------------------------------------------------------------------
def pack_binary(resolved: Sequence[Resolved], wechat_version: str) -> bytes:
    """
    二进制格式（全部 XOR 混淆，避免明文类名落进 DEX/资源）：
      magic(4) ver(u16) count(u16) wechat_len(u8)+bytes
      逐条: key_len(u8)+key desc_len(u16)+desc hash(4)
    """
    v = wechat_version.encode("utf-8")
    plaintext_header = bytearray()
    plaintext_header.append(len(v) & 0xFF)
    plaintext_header += v
    payload = bytearray()
    for r in resolved:
        key = r.key.encode("utf-8")
        desc = r.descriptor.encode("utf-8")
        payload.append(len(key) & 0xFF)
        payload += key
        payload += struct.pack("<H", len(desc))
        payload += desc
        payload += struct.pack("<I", int(r.code_hash, 16))
    # 滚动 XOR：key 由版本与记录数派生，解密逻辑在 Kotlin 侧一致
    seed = (zlib.crc32(wechat_version.encode("utf-8")) ^ 0xA5A5A5A5) & 0xFF
    out = bytearray()
    for i, b in enumerate(payload):
        out.append(b ^ ((seed + i * 31) & 0xFF))
    header = MAGIC + struct.pack("<HH", FORMAT_VERSION, len(resolved))
    return bytes(header) + bytes(plaintext_header) + bytes(out)


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--apk", required=True, help="微信 APK 路径")
    ap.add_argument("--spec", required=True, help="锚点清单 JSON")
    ap.add_argument("--out", required=True, help="输出 descriptors.bin")
    ap.add_argument("--json", help="同时输出可读的 descriptors.json（仅供构建期核对，勿打包）")
    ap.add_argument("--wechat-version", default="8.0.79")
    args = ap.parse_args(argv)

    anchors = load_spec(Path(args.spec))
    dexes = list(dexes_from_apk(Path(args.apk)))
    if not dexes:
        print("APK 中没有 classes*.dex", file=sys.stderr)
        return 2
    print(f"加载 {len(dexes)} 个 dex，{len(anchors)} 个锚点定义")

    resolved, ambiguous = resolve(anchors, dexes)
    for r in resolved:
        print(f"  [OK] {r.key:24s} -> {r.descriptor}")
    for key, descs in ambiguous.items():
        print(f"  [AMBIG] {key}: {len(descs)} 个候选，取首个：{descs[0]}", file=sys.stderr)

    missing = [a.key for a in anchors if a.key not in {r.key for r in resolved}]
    if missing:
        print("未命中锚点： " + ", ".join(missing), file=sys.stderr)

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(pack_binary(resolved, args.wechat_version))
    print(f"写入 {out} ({out.stat().st_size} bytes)")

    if args.json:
        jp = Path(args.json)
        jp.parent.mkdir(parents=True, exist_ok=True)
        jp.write_text(
            json.dumps(
                {
                    "wechat_version": args.wechat_version,
                    "resolved": [asdict(r) for r in resolved],
                    "ambiguous": ambiguous,
                    "missing": missing,
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )
        print(f"写入 {jp}")
    return 0 if not missing else 1


if __name__ == "__main__":
    raise SystemExit(main())
