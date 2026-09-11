const fs = require('fs');
const path = require('path');

function pad4(n) { return (4 - (n % 4)) % 4; }

const COMP_SIZE = { 5120: 1, 5121: 1, 5122: 2, 5123: 2, 5125: 4, 5126: 4 };
const TYPE_COUNT = { SCALAR: 1, VEC2: 2, VEC3: 3, VEC4: 4, MAT2: 4, MAT3: 9, MAT4: 16 };
function byteLen(a) {
  const cs = COMP_SIZE[a.componentType] || 4;
  const tc = TYPE_COUNT[a.type] || 1;
  return a.count * cs * tc;
}

function readGlb(file) {
  const b = fs.readFileSync(file);
  if (b.toString('ascii', 0, 4) !== 'glTF') throw new Error(file + ': not GLB');
  const jsonLen = b.readUInt32LE(12);
  let s = b.toString('utf8', 20, 20 + jsonLen);
  const cut = s.lastIndexOf('}');
  const json = JSON.parse(s.slice(0, cut + 1));
  let off = 20 + jsonLen, bin = Buffer.alloc(0);
  while (off + 8 <= b.length) {
    const len = b.readUInt32LE(off), type = b.readUInt32LE(off + 4);
    if (type === 0x004e4942) { bin = b.slice(off + 8, off + 8 + len); break; }
    off += 8 + len;
  }
  return { json, bin, name: path.basename(file, '.glb') };
}

function stripTextures(g) {
  delete g.images; delete g.textures;
  for (const m of g.materials || []) {
    const p = m.pbrMetallicRoughness = m.pbrMetallicRoughness || {};
    p.baseColorFactor = [1, 1, 1, 1]; p.metallicFactor = 0; p.roughnessFactor = 1;
    delete p.baseColorTexture; delete p.metallicRoughnessTexture;
    delete m.normalTexture; delete m.occlusionTexture; delete m.emissiveTexture;
    delete m.emissiveFactor; delete m.doubleSided; delete m.alphaMode;
  }
}

function main() {
  const [baseFile, outFile] = [process.argv[2], process.argv[3]];
  const animFiles = process.argv.slice(4);
  const base = readGlb(baseFile);
  stripTextures(base.json);
  const baseNameIndex = new Map(base.json.nodes.map((n, i) => [n.name, i]));

  const pieces = [base.bin];
  let cursor = base.bin.length;
  let added = 0;
  const skip = [];

  for (const f of animFiles) {
    const src = readGlb(f);
    const anim = (src.json.animations || []).find(a => a.samplers && a.samplers.length);
    if (!anim) { skip.push(path.basename(f) + ' (no anim)'); continue; }
    const clipName = path.basename(f, '.glb').replace(/[^A-Za-z0-9_.-]/g, '_');

    if (anim.samplers.length !== anim.channels.length) { skip.push(clipName + ' (sampler/channel mismatch)'); continue; }
    let malformed = false;
    for (const s of anim.samplers) {
      const ai = src.json.accessors[s.input], ao = src.json.accessors[s.output];
      if (!ai || !ao || ai.type !== 'SCALAR') { malformed = true; break; }
    }
    if (malformed) { skip.push(clipName + ' (invalid sampler format)'); continue; }

    const samplers = [], channels = [];
    const accMap = new Map();
    const clipStart = cursor;
    const cpy = (sai) => {
      if (accMap.has(sai)) return accMap.get(sai);
      const a = src.json.accessors[sai];
      if (!a) throw new Error('missing src accessor ' + sai + ' in ' + f);
      const v = a.bufferView != null ? src.json.bufferViews[a.bufferView] : null;
      let ni = null, vo = a.byteOffset || 0;
      if (v != null) {
        const abs = v.byteOffset + (a.byteOffset || 0);
        const len = byteLen(a);
        ni = { buffer: 0, byteOffset: cursor, byteLength: len };
        pieces.push(src.bin.slice(abs, abs + len));
        cursor += len + pad4(len);
        vo = 0;
      }
      const na = { componentType: a.componentType, type: a.type, count: a.count };
      if (a.min) na.min = a.min;
      if (a.max) na.max = a.max;
      if (ni) { na.bufferView = base.json.bufferViews.length; base.json.bufferViews.push(ni); }
      na.byteOffset = vo;
      const idx = base.json.accessors.length;
      accMap.set(sai, idx);
      base.json.accessors.push(na);
      return idx;
    };

    for (const ch of anim.channels) {
      const name = src.json.nodes[ch.target.node]?.name;
      if (!name || !baseNameIndex.has(name)) continue;
      channels.push({ sampler: ch.sampler, target: { node: baseNameIndex.get(name), path: ch.target.path } });
    }
    if (!channels.length) { skip.push(clipName + ' (no channels mapped)'); continue; }
    if (channels.length !== anim.samplers.length) { skip.push(clipName + ' (channel count mismatch ' + channels.length + '/' + anim.samplers.length + ')'); continue; }
    for (const s of anim.samplers) {
      samplers.push({ input: cpy(s.input), output: cpy(s.output), interpolation: s.interpolation || 'LINEAR' });
    }
    base.json.animations.push({ name: clipName, samplers, channels });
    added++;
    console.log('  + ' + clipName, 'channels ' + channels.length, ((cursor - clipStart) / 1024).toFixed(0) + 'KB');
  }

  const final = Buffer.concat(pieces);
  base.json.buffers[0].byteLength = final.length;

  const jsonBuf = Buffer.from(JSON.stringify(base.json), 'utf8');
  const pj = pad4(jsonBuf.length), pb = pad4(final.length);
  const total = 12 + 8 + jsonBuf.length + pj + 8 + final.length + pb;
  const out = Buffer.alloc(total);
  out.write('glTF', 0); out.writeUInt32LE(2, 4); out.writeUInt32LE(total, 8);
  let off = 12;
  out.writeUInt32LE(jsonBuf.length + pj, off); out.writeUInt32LE(0x4e4f534a, off + 4); off += 8;
  jsonBuf.copy(out, off); out.fill(0x20, off + jsonBuf.length, off + jsonBuf.length + pj); off += jsonBuf.length + pj;
  out.writeUInt32LE(final.length + pb, off); out.writeUInt32LE(0x004e4942, off + 4); off += 8;
  final.copy(out, off);
  fs.writeFileSync(outFile, out);
  console.log('WROTE', outFile, (out.length / 1048576).toFixed(2), 'MB, animations:', base.json.animations.length,
    base.json.animations.map(a => a.name + '(' + a.channels.length + ')').join(' '));
  if (skip.length) console.log('SKIPPED:', skip.join(' | '));
}

main();