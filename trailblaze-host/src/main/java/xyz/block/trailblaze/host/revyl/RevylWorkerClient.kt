package xyz.block.trailblaze.host.revyl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * HTTP client that communicates with a Revyl cloud device worker
 * and the Revyl backend for session provisioning and AI-powered target resolution.
 *
 * Uses the same HTTP endpoints as the Revyl CLI implementation.
 *
 * @property apiKey Revyl API key for backend authentication.
 * @property backendBaseUrl Base URL for the Revyl backend API.
 */
class RevylWorkerClient(
  private val apiKey: String,
  private val backendBaseUrl: String = DEFAULT_BACKEND_URL,
) {

  private val json = Json { ignoreUnknownKeys = true }

  private val httpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

  private val revylCliBinary = System.getenv("REVYL_CLI_BIN")?.takeIf { it.isNotBlank() } ?: "revyl"
  private val useRevylCli = System.getenv("TRAILBLAZE_REVYL_USE_CLI")?.lowercase() != "false"
  private val revylCliAvailable: Boolean by lazy { detectRevylCli() }

  private var currentSession: RevylSession? = null

  /**
   * Returns the currently active session, or null if none is provisioned.
   */
  fun getSession(): RevylSession? = currentSession

  // ---------------------------------------------------------------------------
  // Session lifecycle
  // ---------------------------------------------------------------------------

  /**
   * Provisions a new cloud-hosted device and polls until the worker is reachable.
   *
   * @param platform "ios" or "android".
   * @param appUrl Optional direct URL to an .apk/.ipa to install.
   * @param appLink Optional deep-link to open after launch.
   * @return The newly created [RevylSession].
   * @throws RevylApiException If provisioning fails or the worker never becomes ready.
   */
  fun startSession(
    platform: String,
    appUrl: String? = null,
    appLink: String? = null,
  ): RevylSession {
    if (shouldUseRevylCli()) {
      return startSessionViaCli(platform, appUrl, appLink)
    }

    val body = buildJsonObject {
      put("platform", platform.lowercase())
      put("is_simulation", true)
      if (!appUrl.isNullOrBlank()) put("app_url", appUrl)
      if (!appLink.isNullOrBlank()) put("app_link", appLink)
    }

    val response = backendPost("/api/v1/execution/start_device", body)
    val workflowRunId = response["workflow_run_id"]?.jsonPrimitive?.content ?: ""
    if (workflowRunId.isBlank()) {
      val error = response["error"]?.jsonPrimitive?.content ?: "unknown error"
      throw RevylApiException("Failed to start device: $error")
    }

    val workerBaseUrl = pollForWorkerUrl(workflowRunId, maxWaitSeconds = 120)
    waitForDeviceReady(workerBaseUrl, maxWaitSeconds = 30)

    val viewerUrl = "$backendBaseUrl/tests/execute?workflowRunId=$workflowRunId&platform=$platform"

    val session = RevylSession(
      index = 0,
      sessionId = workflowRunId,
      workflowRunId = workflowRunId,
      workerBaseUrl = workerBaseUrl,
      viewerUrl = viewerUrl,
      platform = platform.lowercase(),
    )
    currentSession = session
    return session
  }

  /**
   * Stops (cancels) the current device session and releases cloud resources.
   *
   * @throws RevylApiException If the cancellation request fails.
   */
  fun stopSession() {
    val session = currentSession ?: return
    if (shouldUseRevylCli()) {
      try {
        runRevylCli(
          "device",
          "stop",
          "-s",
          session.index.toString(),
        )
        currentSession = null
        return
      } catch (_: Exception) {
        // Fall back to direct backend cancel.
      }
    }

    backendPost(
      "/api/v1/execution/device/status/cancel/${session.workflowRunId}",
      buildJsonObject { },
    )
    currentSession = null
  }

  // ---------------------------------------------------------------------------
  // Device actions — delegated to the worker HTTP API
  // ---------------------------------------------------------------------------

  /**
   * Captures a PNG screenshot of the current device screen.
   *
   * @return Raw PNG bytes.
   * @throws RevylApiException If no session is active or the request fails.
   */
  fun screenshot(): ByteArray {
    val session = requireSession()
    if (shouldUseRevylCli()) {
      try {
        return screenshotViaCli(session.index)
      } catch (_: Exception) {
        // Fall back to direct worker/backend proxy.
      }
    }

    return try {
      val request = Request.Builder()
        .url("${session.workerBaseUrl}/screenshot")
        .get()
        .build()

      val response = httpClient.newCall(request).execute()
      if (!response.isSuccessful) {
        throw RevylApiException("Screenshot failed: HTTP ${response.code}")
      }
      response.body?.bytes() ?: throw RevylApiException("Screenshot returned empty body")
    } catch (_: IOException) {
      proxyWorkerGetBytes(session.workflowRunId, action = "screenshot")
    }
  }

  /**
   * Taps at the given coordinates on the device screen.
   *
   * @param x Horizontal pixel coordinate.
   * @param y Vertical pixel coordinate.
   * @throws RevylApiException If the request fails.
   */
  fun tap(x: Int, y: Int) {
    val session = requireSession()
    if (shouldUseRevylCli()) {
      try {
        runRevylCli(
          "device",
          "tap",
          "-s",
          session.index.toString(),
          "--x",
          x.toString(),
          "--y",
          y.toString(),
        )
        return
      } catch (_: Exception) {
        // Fall back to direct worker/backend proxy.
      }
    }

    workerPost("/tap", buildJsonObject { put("x", x); put("y", y) })
  }

  /**
   * Taps a UI element identified by a natural language description
   * using the Revyl AI grounding model.
   *
   * @param target Natural language description (e.g. "Sign In button").
   * @throws RevylApiException If grounding or the tap fails.
   */
  fun tapTarget(target: String) {
    val session = requireSession()
    if (shouldUseRevylCli()) {
      try {
        runRevylCli(
          "device",
          "tap",
          "-s",
          session.index.toString(),
          "--target",
          target,
        )
        return
      } catch (_: Exception) {
        // Fall back to coordinate resolution + tap.
      }
    }

    val (x, y) = resolveTarget(target)
    tap(x, y)
  }

  /**
   * Long-presses a UI element identified by a natural language description
   * using the Revyl AI grounding model.
   *
   * @param target Natural language description (e.g. "Profile avatar").
   * @param durationMs Duration of the press in milliseconds.
   * @throws RevylApiException If grounding or the long-press fails.
   */
  fun longPressTarget(target: String, durationMs: Int = 1500) {
    val session = requireSession()
    if (shouldUseRevylCli()) {
      try {
        runRevylCli(
          "device",
          "long-press",
          "-s",
          session.index.toString(),
          "--target",
          target,
          "--duration",
          durationMs.toString(),
        )
        return
      } catch (_: Exception) {
        // Fall back to coordinate resolution + long press.
      }
    }

    val (x, y) = resolveTarget(target)
    longPress(x, y, durationMs)
  }

  /**
   * Types text into the currently focused input field or a targeted element.
   *
   * @param text The text to type.
   * @param targetX Optional x coordinate to tap before typing.
   * @param targetY Optional y coordinate to tap before typing.
   * @param clearFirst If true, clears the field content before typing.
   * @throws RevylApiException If the request fails.
   */
  fun typeText(text: String, targetX: Int? = null, targetY: Int? = null, clearFirst: Boolean = false) {
    val session = requireSession()
    if (shouldUseRevylCli()) {
      try {
        val args = mutableListOf(
          "device",
          "type",
          "-s",
          session.index.toString(),
          "--text",
          text,
          "--clear-first=$clearFirst",
        )
        if (targetX != null && targetY != null) {
          args.addAll(listOf("--x", targetX.toString(), "--y", targetY.toString()))
        } else {
          args.addAll(listOf("--target", "focused input field"))
        }
        runRevylCli(*args.toTypedArray())
        return
      } catch (_: Exception) {
        // Fall back to direct worker/backend proxy.
      }
    }

    val body = buildJsonObject {
      put("text", text)
      if (targetX != null && targetY != null) {
        put("x", targetX)
        put("y", targetY)
      }
      if (clearFirst) {
        put("clear_first", true)
      }
    }
    workerPost("/input", body)
  }

  /**
   * Swipes in the given direction from the center or a specific point.
   *
   * @param direction One of "up", "down", "left", "right".
   * @param startX Optional starting x coordinate.
   * @param startY Optional starting y coordinate.
   * @throws RevylApiException If the request fails.
   */
  fun swipe(direction: String, startX: Int? = null, startY: Int? = null) {
    val session = requireSession()
    if (shouldUseRevylCli()) {
      try {
        val args = mutableListOf(
          "device",
          "swipe",
          "-s",
          session.index.toString(),
          "--direction",
          direction,
        )
        if (startX != null && startY != null) {
          args.addAll(listOf("--x", startX.toString(), "--y", startY.toString()))
        } else {
          args.addAll(listOf("--target", "screen center"))
        }
        runRevylCli(*args.toTypedArray())
        return
      } catch (_: Exception) {
        // Fall back to direct worker/backend proxy.
      }
    }

    val body = buildJsonObject {
      put("direction", direction)
      if (startX != null) put("x", startX)
      if (startY != null) put("y", startY)
    }
    workerPost("/swipe", body)
  }

  /**
   * Long-presses at the given coordinates.
   *
   * @param x Horizontal pixel coordinate.
   * @param y Vertical pixel coordinate.
   * @param durationMs Duration of the press in milliseconds.
   * @throws RevylApiException If the request fails.
   */
  fun longPress(x: Int, y: Int, durationMs: Int = 1500) {
    val session = requireSession()
    if (shouldUseRevylCli()) {
      try {
        runRevylCli(
          "device",
          "long-press",
          "-s",
          session.index.toString(),
          "--x",
          x.toString(),
          "--y",
          y.toString(),
          "--duration",
          durationMs.toString(),
        )
        return
      } catch (_: Exception) {
        // Fall back to direct worker/backend proxy.
      }
    }

    val body = buildJsonObject {
      put("x", x)
      put("y", y)
      put("duration", durationMs)
    }
    workerPost("/longpress", body)
  }

  /**
   * Installs an app from a URL onto the device.
   *
   * @param appUrl Direct download URL for the .apk or .ipa.
   * @throws RevylApiException If the request fails.
   */
  fun installApp(appUrl: String) {
    val session = requireSession()
    if (shouldUseRevylCli()) {
      try {
        runRevylCli(
          "device",
          "install",
          "-s",
          session.index.toString(),
          "--app-url",
          appUrl,
        )
        return
      } catch (_: Exception) {
        // Fall back to direct worker/backend proxy.
      }
    }

    workerPost("/install", buildJsonObject { put("app_url", appUrl) })
  }

  /**
   * Launches an installed app by its bundle/package ID.
   *
   * @param bundleId The app's bundle identifier (iOS) or package name (Android).
   * @throws RevylApiException If the request fails.
   */
  fun launchApp(bundleId: String) {
    val session = requireSession()
    if (shouldUseRevylCli()) {
      try {
        runRevylCli(
          "device",
          "launch",
          "-s",
          session.index.toString(),
          "--bundle-id",
          bundleId,
        )
        return
      } catch (_: Exception) {
        // Fall back to direct worker/backend proxy.
      }
    }

    workerPost("/launch", buildJsonObject { put("bundle_id", bundleId) })
  }

  /**
   * Resolves a natural language target description to screen coordinates
   * using the Revyl AI grounding model on the worker.
   *
   * Falls back to the backend grounding endpoint if the worker doesn't support
   * native target resolution.
   *
   * @param target Natural language element description (e.g. "the search bar").
   * @return Pair of (x, y) pixel coordinates.
   * @throws RevylApiException If grounding fails.
   */
  fun resolveTarget(target: String): Pair<Int, Int> {
    val session = requireSession()

    // Try worker-native grounding first
    try {
      val body = buildJsonObject { put("target", target) }
      val responseBody = workerPostRaw("/resolve_target", body)
      val parsed = json.parseToJsonElement(responseBody).jsonObject
      val x = parsed["x"]!!.jsonPrimitive.int
      val y = parsed["y"]!!.jsonPrimitive.int
      return Pair(x, y)
    } catch (_: Exception) {
      // Fall back to backend grounding
    }

    // Backend grounding fallback: send screenshot + target to the backend
    val screenshotBytes = screenshot()
    val base64Screenshot = java.util.Base64.getEncoder().encodeToString(screenshotBytes)
    val (width, height) = pngDimensions(screenshotBytes)
    val groundBody = buildJsonObject {
      put("image_base64", base64Screenshot)
      put("target", target)
      if (width > 0) put("width", width)
      if (height > 0) put("height", height)
      put("platform", session.platform)
      put("session_id", session.sessionId)
    }

    val response = backendPost("/api/v1/execution/ground", groundBody)
    val x = response["x"]?.jsonPrimitive?.intOrNull
      ?: response["x"]?.jsonPrimitive?.content?.toIntOrNull()
      ?: throw RevylApiException("Ground response missing numeric x")
    val y = response["y"]?.jsonPrimitive?.intOrNull
      ?: response["y"]?.jsonPrimitive?.content?.toIntOrNull()
      ?: throw RevylApiException("Ground response missing numeric y")
    return Pair(x, y)
  }

  // ---------------------------------------------------------------------------
  // Internal helpers
  // ---------------------------------------------------------------------------

  private fun requireSession(): RevylSession =
    currentSession ?: throw RevylApiException("No active device session. Call startSession() first.")

  private fun backendPost(path: String, body: JsonObject): JsonObject {
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val request = Request.Builder()
      .url("$backendBaseUrl$path")
      .addHeader("Authorization", "Bearer $apiKey")
      .addHeader("X-API-Key", apiKey)
      .addHeader("Content-Type", "application/json")
      .post(body.toString().toRequestBody(mediaType))
      .build()

    val response = httpClient.newCall(request).execute()
    val responseBody = response.body?.string() ?: "{}"
    if (!response.isSuccessful) {
      throw RevylApiException("Backend request to $path failed (HTTP ${response.code}): $responseBody")
    }
    return json.parseToJsonElement(responseBody).jsonObject
  }

  private fun workerPost(path: String, body: JsonObject) {
    val responseBody = workerPostRaw(path, body)
    if (responseBody.isNotBlank()) {
      try {
        val parsed = json.parseToJsonElement(responseBody).jsonObject
        if (parsed.containsKey("error")) {
          throw RevylApiException("Worker action $path failed: ${parsed["error"]!!.jsonPrimitive.content}")
        }
      } catch (_: kotlinx.serialization.SerializationException) {
        // Non-JSON response is fine for success
      }
    }
  }

  private fun workerPostRaw(path: String, body: JsonObject): String {
    val session = requireSession()
    return try {
      val mediaType = "application/json; charset=utf-8".toMediaType()
      val request = Request.Builder()
        .url("${session.workerBaseUrl}$path")
        .addHeader("Content-Type", "application/json")
        .post(body.toString().toRequestBody(mediaType))
        .build()

      val response = httpClient.newCall(request).execute()
      val responseBody = response.body?.string() ?: ""
      if (!response.isSuccessful) {
        throw RevylApiException("Worker request to $path failed (HTTP ${response.code}): $responseBody")
      }
      responseBody
    } catch (_: IOException) {
      proxyWorkerPost(
        workflowRunId = session.workflowRunId,
        action = path.removePrefix("/"),
        body = body,
      )
    }
  }

  /**
   * Polls the backend until a worker URL is available for the given workflow run.
   */
  private fun pollForWorkerUrl(workflowRunId: String, maxWaitSeconds: Int): String {
    val deadline = System.currentTimeMillis() + (maxWaitSeconds * 1000L)
    while (System.currentTimeMillis() < deadline) {
      try {
        val request = Request.Builder()
          .url("$backendBaseUrl/api/v1/execution/streaming/worker-connection/$workflowRunId")
          .addHeader("Authorization", "Bearer $apiKey")
          .addHeader("X-API-Key", apiKey)
          .get()
          .build()

        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
          val body = response.body?.string() ?: ""
          val parsed = json.parseToJsonElement(body).jsonObject
          val workerWsUrl = parsed["worker_ws_url"]?.jsonPrimitive?.content
            ?.takeUnless { it.isBlank() || it == "null" }
            ?: ""
          if (workerWsUrl.isNotBlank()) {
            return wsUrlToHttpBase(workerWsUrl)
          }

          val status = parsed["status"]?.jsonPrimitive?.content?.lowercase() ?: ""
          val message = parsed["message"]?.jsonPrimitive?.content
            ?.takeUnless { it.isBlank() || it == "null" }
            ?: "unknown reason"
          if (status == "failed" || status == "cancelled" || status == "stopped") {
            throw RevylApiException("Worker connection status '$status': $message")
          }
        }
      } catch (_: IOException) {
        // Retry
      }
      Thread.sleep(2000)
    }
    throw RevylApiException("Worker URL not available after ${maxWaitSeconds}s for workflow $workflowRunId")
  }

  /**
   * Waits until the worker's health endpoint reports a connected device.
   */
  private fun waitForDeviceReady(workerBaseUrl: String, maxWaitSeconds: Int) {
    val deadline = System.currentTimeMillis() + (maxWaitSeconds * 1000L)
    while (System.currentTimeMillis() < deadline) {
      try {
        val request = Request.Builder()
          .url("$workerBaseUrl/health")
          .get()
          .build()

        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
          return
        }
      } catch (_: IOException) {
        // Some environments cannot reach worker DNS directly; ignore and continue.
        // Retry
      }
      Thread.sleep(2000)
    }
  }

  private fun proxyWorkerPost(workflowRunId: String, action: String, body: JsonObject): String {
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val request = Request.Builder()
      .url("$backendBaseUrl/api/v1/execution/device-proxy/$workflowRunId/$action")
      .addHeader("Authorization", "Bearer $apiKey")
      .addHeader("X-API-Key", apiKey)
      .addHeader("Content-Type", "application/json")
      .post(body.toString().toRequestBody(mediaType))
      .build()
    val response = httpClient.newCall(request).execute()
    val responseBody = response.body?.string() ?: ""
    if (!response.isSuccessful) {
      throw RevylApiException("Proxy request '$action' failed (HTTP ${response.code}): $responseBody")
    }
    return responseBody
  }

  private fun proxyWorkerGetBytes(workflowRunId: String, action: String): ByteArray {
    val request = Request.Builder()
      .url("$backendBaseUrl/api/v1/execution/device-proxy/$workflowRunId/$action")
      .addHeader("Authorization", "Bearer $apiKey")
      .addHeader("X-API-Key", apiKey)
      .get()
      .build()
    val response = httpClient.newCall(request).execute()
    if (!response.isSuccessful) {
      val responseBody = response.body?.string() ?: ""
      throw RevylApiException("Proxy request '$action' failed (HTTP ${response.code}): $responseBody")
    }
    return response.body?.bytes() ?: throw RevylApiException("Proxy request '$action' returned empty body")
  }

  private fun wsUrlToHttpBase(wsUrl: String): String {
    val normalized = wsUrl
      .replaceFirst("wss://", "https://")
      .replaceFirst("ws://", "http://")
    return try {
      val uri = URI(normalized)
      val host = uri.host ?: return normalized.substringBefore("/ws/").trimEnd('/')
      val portSegment = if (uri.port == -1) "" else ":${uri.port}"
      "${uri.scheme}://$host$portSegment".trimEnd('/')
    } catch (_: Exception) {
      normalized.substringBefore("/ws/").trimEnd('/')
    }
  }

  private fun shouldUseRevylCli(): Boolean = useRevylCli && revylCliAvailable

  private fun detectRevylCli(): Boolean {
    return try {
      val process = ProcessBuilder(revylCliBinary, "--version")
        .redirectErrorStream(true)
        .start()
      process.inputStream.bufferedReader().use { it.readText() }
      process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
    } catch (_: Exception) {
      false
    }
  }

  private fun runRevylCli(vararg args: String): String {
    val command = mutableListOf(revylCliBinary, "--json")
    command.addAll(args)
    val processBuilder = ProcessBuilder(command)
    processBuilder.redirectErrorStream(true)
    processBuilder.environment()["REVYL_API_KEY"] = apiKey
    if (backendBaseUrl != DEFAULT_BACKEND_URL) {
      processBuilder.environment()["REVYL_BACKEND_URL"] = backendBaseUrl
    }
    val process = processBuilder.start()
    val stdout = process.inputStream.bufferedReader().use { it.readText() }.trim()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
      val details = stdout
      throw RevylApiException(
        "Revyl CLI command failed (${command.joinToString(" ")}): ${details.ifBlank { "exit code $exitCode" }}"
      )
    }
    return stdout
  }

  private fun runRevylCliJson(vararg args: String): JsonObject {
    val stdout = runRevylCli(*args).ifBlank { "{}" }
    return json.parseToJsonElement(stdout).jsonObject
  }

  private fun startSessionViaCli(platform: String, appUrl: String?, appLink: String?): RevylSession {
    val args = mutableListOf(
      "device",
      "start",
      "--platform",
      platform.lowercase(),
    )
    if (!appUrl.isNullOrBlank()) {
      args.addAll(listOf("--app-url", appUrl))
    }
    if (!appLink.isNullOrBlank()) {
      args.addAll(listOf("--app-link", appLink))
    }

    val response = runRevylCliJson(*args.toTypedArray())
    val workflowRunId = response["workflow_run_id"]?.jsonPrimitive?.content
      ?.takeUnless { it.isBlank() || it == "null" }
      ?: throw RevylApiException("Revyl CLI did not return workflow_run_id")
    val sessionId = response["session_id"]?.jsonPrimitive?.content
      ?.takeUnless { it.isBlank() || it == "null" }
      ?: workflowRunId
    val workerBaseUrl = response["worker_base_url"]?.jsonPrimitive?.content
      ?.takeUnless { it.isBlank() || it == "null" }
      ?: pollForWorkerUrl(workflowRunId, maxWaitSeconds = 120)
    val viewerUrl = response["viewer_url"]?.jsonPrimitive?.content
      ?.takeUnless { it.isBlank() || it == "null" }
      ?: "$backendBaseUrl/tests/execute?workflowRunId=$workflowRunId&platform=${platform.lowercase()}"
    val sessionIndex = response["index"]?.jsonPrimitive?.intOrNull ?: 0

    val session = RevylSession(
      index = sessionIndex,
      sessionId = sessionId,
      workflowRunId = workflowRunId,
      workerBaseUrl = workerBaseUrl,
      viewerUrl = viewerUrl,
      platform = platform.lowercase(),
    )
    currentSession = session
    return session
  }

  private fun screenshotViaCli(sessionIndex: Int): ByteArray {
    val tempFile = File.createTempFile("trailblaze-revyl-", ".png")
    return try {
      runRevylCli(
        "device",
        "screenshot",
        "-s",
        sessionIndex.toString(),
        "--out",
        tempFile.absolutePath,
      )
      if (!tempFile.exists() || tempFile.length() == 0L) {
        throw RevylApiException("Revyl CLI screenshot did not produce image bytes")
      }
      tempFile.readBytes()
    } finally {
      tempFile.delete()
    }
  }

  private fun pngDimensions(data: ByteArray): Pair<Int, Int> {
    // PNG signature + IHDR header + width/height slots.
    if (data.size < 24) return 0 to 0
    val pngSig = byteArrayOf(
      0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
      0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
    )
    for (i in pngSig.indices) {
      if (data[i] != pngSig[i]) return 0 to 0
    }

    fun readUInt32(offset: Int): Int {
      return ((data[offset].toInt() and 0xFF) shl 24) or
        ((data[offset + 1].toInt() and 0xFF) shl 16) or
        ((data[offset + 2].toInt() and 0xFF) shl 8) or
        (data[offset + 3].toInt() and 0xFF)
    }

    val width = readUInt32(16)
    val height = readUInt32(20)
    if (width <= 0 || height <= 0) return 0 to 0
    return width to height
  }

  companion object {
    const val DEFAULT_BACKEND_URL = "https://backend.revyl.ai"
  }
}

/**
 * Exception thrown when a Revyl API or worker request fails.
 *
 * @property message Human-readable description of the failure.
 */
class RevylApiException(message: String) : RuntimeException(message)
