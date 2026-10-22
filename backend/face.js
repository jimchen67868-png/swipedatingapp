import { createRequire } from "module";
import fs from "node:fs";
import os from "node:os";
import path from "path";

const require = createRequire(import.meta.url);
const THRESHOLD = Number(process.env.FACE_MATCH_THRESHOLD) || 0.55; // lower = stricter (0.6 is the library default)

let loading;
function load() {
  loading ??= (async () => {
    const tf = require("@tensorflow/tfjs-node");
    const faceapi = require("@vladmandic/face-api");
    const dir = process.env.FACE_MODEL_DIR ?? path.join(path.dirname(require.resolve("@vladmandic/face-api")), "..", "model");
    await faceapi.nets.ssdMobilenetv1.loadFromDisk(dir);
    await faceapi.nets.faceLandmark68Net.loadFromDisk(dir);
    await faceapi.nets.faceRecognitionNet.loadFromDisk(dir);
    return { tf, faceapi };
  })().catch((e) => {
    loading = undefined; // allow a retry on the next request
    throw e;
  });
  return loading;
}

async function faces(jpeg) {
  const { tf, faceapi } = await load();
  const img = tf.node.decodeImage(jpeg, 3);
  try {
    return await faceapi
      .detectAllFaces(img, new faceapi.SsdMobilenetv1Options({ minConfidence: 0.5 }))
      .withFaceLandmarks()
      .withFaceDescriptors();
  } finally {
    img.dispose();
  }
}

async function run(selfie, photo) {
  const { faceapi } = await load();
  const s = await faces(selfie);
  if (s.length === 0) return { ok: false, reason: "no_face_selfie" };
  if (s.length > 1) return { ok: false, reason: "many_faces" };
  const p = await faces(photo);
  if (p.length === 0) return { ok: false, reason: "no_face_photo" };
  // If the profile photo has several people, accept a match with any of them.
  const distance = Math.min(...p.map((f) => faceapi.euclideanDistance(s[0].descriptor, f.descriptor)));
  return { ok: true, distance, match: distance <= THRESHOLD };
}

const NSFW_MAX = Number(process.env.NSFW_MAX) || 0.3;   // porn + hentai score that gets a photo refused
const SAME_MAX = Number(process.env.FACE_SAME_THRESHOLD) || 0.5; // extra photos must be this close to the main photo (lower = stricter)
const SEXY_MAX = Number(process.env.SEXY_MAX) || 0.6;   // 'sexy' score (lingerie, revealing, suggestive) that gets a photo refused

// The nudity model is a small file (about 3 MB), downloaded once from a CDN into the temp folder and loaded from there.
// (The copy bundled inside the nsfwjs package is stored inside JavaScript and runs the free server out of memory.)
const NSFW_BASE = process.env.NSFW_MODEL_URL ?? "https://cdn.jsdelivr.net/gh/infinitered/nsfwjs@master/models/mobilenet_v2/";
const NSFW_CLASSES = ["Drawing", "Hentai", "Neutral", "Porn", "Sexy"];

async function nsfwModelFile() {
  const dir = path.join(os.tmpdir(), "nsfw-model");
  const marker = path.join(dir, "model.json");
  if (!fs.existsSync(marker)) {
    fs.mkdirSync(dir, { recursive: true });
    const get = async (name) => {
      const r = await fetch(NSFW_BASE + name);
      if (!r.ok) throw new Error(`Could not download ${name} (${r.status})`);
      return Buffer.from(await r.arrayBuffer());
    };
    const json = await get("model.json");
    for (const group of JSON.parse(json.toString()).weightsManifest) {
      for (const p of group.paths) fs.writeFileSync(path.join(dir, p), await get(p));
    }
    fs.writeFileSync(marker, json); // written last, so a half-finished download is retried next time
  }
  return "file://" + marker;
}

let nsfwLoading;
function loadNsfw() {
  nsfwLoading ??= (async () => {
    const { tf } = await load();
    const url = await nsfwModelFile();
    const model = await tf.loadLayersModel(url).catch(() => tf.loadGraphModel(url));
    const shape = model.inputs?.[0]?.shape;
    return { model, size: shape?.[1] ?? 224 };
  })().catch((e) => {
    nsfwLoading = undefined; // retry on the next upload
    throw e;
  });
  return nsfwLoading;
}

async function classifyNsfw(tf, { model, size }, img) {
  const out = tf.tidy(() => model.predict(tf.image.resizeBilinear(img.toFloat(), [size, size]).div(255).expandDims(0)));
  const raw = Array.from(await out.data());
  out.dispose();
  const isProbs = raw.every((v) => v >= 0 && v <= 1) && Math.abs(raw.reduce((x, y) => x + y, 0) - 1) < 0.01;
  const exp = raw.map((v) => Math.exp(v - Math.max(...raw)));
  const sum = exp.reduce((x, y) => x + y, 0);
  const probs = isProbs ? raw : exp.map((v) => v / sum);
  return Object.fromEntries(NSFW_CLASSES.map((name, i) => [name, probs[i]]));
}

/**
 * Checks a photo before it is saved. Returns "ok", "explicit", "no_face", "many_faces" or "too_small".
 * Every photo must show exactly one person. main = true also needs a close, clear face. An extra photo (main = false, with the main photo as reference) must be the same person as the main photo. Also returns \"different_person\".
 * Set NSFW_CHECK=off to skip the explicit-content check.
 */
async function photoCheck(jpeg, main, reference) {
  const { tf, faceapi } = await load();

  if (process.env.NSFW_CHECK !== "off") {
    const model = await loadNsfw(); // fails closed: if the model can't load the upload is refused
    const img = tf.node.decodeImage(jpeg, 3);
    try {
      const p = await classifyNsfw(tf, model, img);
      if ((p.Porn ?? 0) + (p.Hentai ?? 0) >= NSFW_MAX) return "explicit";
      if ((p.Sexy ?? 0) >= SEXY_MAX) return "explicit";
    } finally {
      img.dispose();
    }
  }

  const img = tf.node.decodeImage(jpeg, 3);
  try {
    const opts = new faceapi.SsdMobilenetv1Options({ minConfidence: 0.5 });
    const wantMatch = !main && Boolean(reference);
    const found = wantMatch
      ? await faceapi.detectAllFaces(img, opts).withFaceLandmarks().withFaceDescriptors()
      : await faceapi.detectAllFaces(img, opts);
    const width = (d) => (d.detection ?? d).relativeBox.width;
    const people = found.filter((d) => width(d) >= 0.06); // ignore tiny faces far in the background
    if (people.length === 0) return "no_face";
    if (people.length > 1) return "many_faces";
    if (main && width(people[0]) < 0.2) return "too_small";
    if (wantMatch) {
      if (width(people[0]) < 0.08) return "too_small"; // too small to compare reliably
      const ref = await faces(reference);
      if (ref.length > 0) {
        const distance = Math.min(...ref.map((f) => faceapi.euclideanDistance(people[0].descriptor, f.descriptor)));
        console.log(`same-person score ${distance.toFixed(2)} (limit ${SAME_MAX}), face width ${width(people[0]).toFixed(2)}`);
        if (distance > SAME_MAX) return `different_person:${distance.toFixed(2)}:${SAME_MAX}`;
      }
    }
    return "ok";
  } finally {
    img.dispose();
  }
}

export const MATCH_MAX = THRESHOLD;
export const MATCH_ID_MAX = Number(process.env.FACE_ID_THRESHOLD) || 0.6; // ID photos are older and lower quality

async function runMany(selfie, others) {
  const { faceapi } = await load();
  const s = await faces(selfie);
  if (s.length === 0) return { ok: false, reason: "no_face_selfie" };
  if (s.length > 1) return { ok: false, reason: "many_faces" };
  const results = [];
  for (const other of others) {
    const p = await faces(other);
    results.push(
      p.length === 0
        ? { ok: false, reason: "no_face_photo" }
        : { ok: true, distance: Math.min(...p.map((f) => faceapi.euclideanDistance(s[0].descriptor, f.descriptor))) }
    );
  }
  return { ok: true, results };
}

// One check at a time, so a few people verifying together cannot run the free server out of memory.
let chain = Promise.resolve();
export function compareFaces(selfie, photo) {
  const result = chain.then(() => run(selfie, photo));
  chain = result.catch(() => {});
  return result;
}

export function compareSelfie(selfie, others) {
  const result = chain.then(() => runMany(selfie, others));
  chain = result.catch(() => {});
  return result;
}

export function moderatePhoto(jpeg, main, reference) {
  const result = chain.then(() => photoCheck(jpeg, main, reference));
  chain = result.catch(() => {});
  return result;
}
