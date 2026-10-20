import { createRequire } from "module";
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
const SAME_MAX = Number(process.env.FACE_SAME_THRESHOLD) || 0.6; // extra photos must be this close to the main photo (lower = stricter)
const SEXY_MAX = Number(process.env.SEXY_MAX) || 0.6;   // 'sexy' score (lingerie, revealing, suggestive) that gets a photo refused

let nsfwLoading;
function loadNsfw() {
  nsfwLoading ??= (async () => {
    require("@tensorflow/tfjs-node"); // makes sure the fast backend is the one in use
    const mod = require("nsfwjs");
    return (mod.load ?? mod.default.load)();
  })().catch((e) => {
    nsfwLoading = undefined;
    throw e;
  });
  return nsfwLoading;
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
      const p = Object.fromEntries((await model.classify(img)).map((x) => [x.className, x.probability]));
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
      if (width(people[0]) < 0.1) return "too_small"; // too small to compare reliably
      const ref = await faces(reference);
      if (ref.length > 0) {
        const distance = Math.min(...ref.map((f) => faceapi.euclideanDistance(people[0].descriptor, f.descriptor)));
        if (distance > SAME_MAX) return "different_person";
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
