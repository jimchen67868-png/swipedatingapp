package com.example.swipe.data

import com.example.swipe.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

private fun <T> JSONArray.mapObjects(f: (JSONObject) -> T): List<T> = List(length()) { f(getJSONObject(it)) }

private fun JSONObject.toMe() = MyProfile(getString("name"), getInt("age"), getString("city"), getString("gender"), getString("lookingFor"))
private fun JSONObject.toProfile() = Profile(
    getInt("id").toString(), getString("name"), getInt("age"), getString("city"),
    photoUrl = if (has("photo_v") && !isNull("photo_v")) "${BuildConfig.API_BASE_URL}/photo/${getInt("id")}?v=${getInt("photo_v")}" else null
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
        )
            .mapCatching { JSONObject(it).toMe() }

    suspend fun uploadPhoto(jpeg: ByteArray): Result<Unit> =
        api.postBytes("/me/photo", jpeg, "image/jpeg").map { }

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
