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

// One check at a time, so a few people verifying together cannot run the free server out of memory.
let chain = Promise.resolve();
export function compareFaces(selfie, photo) {
  const result = chain.then(() => run(selfie, photo));
  chain = result.catch(() => {});
  return result;
}
