package com.example.swipe.data

import android.util.Base64
import com.example.swipe.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

private fun <T> JSONArray.mapObjects(f: (JSONObject) -> T): List<T> = List(length()) { f(getJSONObject(it)) }

private fun JSONObject.toMe() = MyProfile(getString("name"), getInt("age"), getString("city"), getString("gender"), getString("lookingFor"), optBoolean("verified"), optBoolean("idVerified"), optBoolean("hasPhoto"),
    if (isNull("birthDate") || optString("birthDate").isBlank()) null else optString("birthDate"),
    if (isNull("maxDistanceKm")) null else optInt("maxDistanceKm"))
private fun JSONObject.toProfile() = Profile(
    getInt("id").toString(), getString("name"), getInt("age"), getString("city"),
    photoUrl = if (has("photo_v") && !isNull("photo_v")) "${BuildConfig.API_BASE_URL}/photo/${getInt("id")}?v=${getInt("photo_v")}" else null,
    verified = optBoolean("verified"),
    distanceKm = if (isNull("distance_km")) null else optInt("distance_km", -1).takeIf { it >= 0 },
    extraPhotos = optJSONArray("extra_photos")?.let { a -> List(a.length()) { "${BuildConfig.API_BASE_URL}/photo/extra/${a.getInt(it)}" } }.orEmpty()
)

/** Talks to the backend. Every call returns a Result so the UI can show errors. */
class Repository(private val api: Api) {

    /** null means the user has not created a profile yet. */
    suspend fun me(): Result<MyProfile?> = api.get("/me").fold(
        onSuccess = { Result.success(JSONObject(it).toMe()) },
        onFailure = { e -> if ((e as? ApiException)?.status == 404) Result.success(null) else Result.failure(e) }
    )

    suspend fun saveMe(p: MyProfile): Result<MyProfile> =
        api.put(
            "/me",
            JSONObject().put("name", p.name).put("age", p.age).put("city", p.city)
                .put("gender", p.gender).put("lookingFor", p.lookingFor)
                .put("maxDistanceKm", p.maxDistanceKm ?: JSONObject.NULL)
                .apply {
                    p.birthDate?.let { put("birthDate", it) }
                    if (p.lat != null && p.lon != null) put("lat", p.lat).put("lon", p.lon)
                }
        )
            .mapCatching { JSONObject(it).toMe() }

    suspend fun uploadPhoto(jpeg: ByteArray): Result<Unit> =
        api.postBytes("/me/photo", jpeg, "image/jpeg").map { }

    suspend fun verify(selfie: ByteArray): Result<Unit> =
        api.postBytes("/me/verify", selfie, "image/jpeg").map { }

    suspend fun verifyId(idPhoto: ByteArray, selfie: ByteArray, dob: List<String>): Result<Unit> =
        api.post(
            "/me/verify-id",
            JSONObject()
                .put("idPhoto", Base64.encodeToString(idPhoto, Base64.NO_WRAP))
                .put("selfie", Base64.encodeToString(selfie, Base64.NO_WRAP))
                .put("dob", JSONArray(dob))
        ).map { }

    suspend fun myPhotos(): Result<MyPhotos> = api.get("/me/photos").map { text ->
        val o = JSONObject(text)
        val a = o.getJSONArray("extras")
        MyPhotos(
            main = if (o.isNull("main")) null else BuildConfig.API_BASE_URL + o.getString("main"),
            extras = List(a.length()) { PhotoSlot(a.getInt(it), "${BuildConfig.API_BASE_URL}/photo/extra/${a.getInt(it)}") }
        )
    }

    suspend fun addPhoto(jpeg: ByteArray): Result<Unit> =
        api.postBytes("/me/photos", jpeg, "image/jpeg").map { }

    suspend fun deletePhoto(id: Int): Result<Unit> =
        api.delete("/me/photos/$id").map { }

    suspend fun report(targetId: String, reason: String): Result<Unit> =
        api.post("/report", JSONObject().put("targetId", targetId.toInt()).put("reason", reason)).map { }

    suspend fun block(targetId: String): Result<Unit> =
        api.post("/block", JSONObject().put("targetId", targetId.toInt())).map { }

    suspend fun deleteAccount(): Result<Unit> = api.delete("/me").map { }

    suspend fun deck(): Result<List<Profile>> =
        api.get("/deck").mapCatching { JSONArray(it).mapObjects { o -> o.toProfile() } }

    /** Returns the match id if this swipe created a match, otherwise null. */
    suspend fun swipe(profileId: String, liked: Boolean): Result<Long?> =
        api.post("/swipe", JSONObject().put("targetId", profileId.toInt()).put("liked", liked)).mapCatching {
            val o = JSONObject(it)
            if (o.optBoolean("matched")) o.getLong("matchId") else null
        }

    suspend fun matches(): Result<List<MatchItem>> =
        api.get("/matches").mapCatching { text ->
            JSONArray(text).mapObjects { o ->
                MatchItem(
                    matchId = o.getLong("match_id"),
                    profile = o.toProfile(),
                    lastMessage = if (o.isNull("last_message")) null else o.getString("last_message")
                )
            }
        }

    suspend fun messages(matchId: Long, after: Long): Result<List<Message>> =
        api.get("/matches/$matchId/messages?after=$after").mapCatching { text ->
            JSONArray(text).mapObjects { o -> Message(o.getLong("id"), o.getBoolean("mine"), o.getString("body")) }
        }

    suspend fun send(matchId: Long, text: String): Result<Unit> =
        api.post("/matches/$matchId/messages", JSONObject().put("body", text)).map { }
}
