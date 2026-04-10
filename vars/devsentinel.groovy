#!/usr/bin/env groovy

import com.devsentinel.Config
import com.devsentinel.HttpClient

/**
 * devsentinel.groovy — DevSentinel AI : Shared Library API (Universal)
 * ======================================================================
 * Compatible avec :
 *   - Declarative Pipeline  (pipeline { stages { ... } })
 *   - Scripted Pipeline     (node { stage(...) { ... } })
 *   - Multibranch Pipeline  (branche auto-détectée)
 *   - Tout type de projet   (Maven, Gradle, Node, Python, Docker...)
 */

@groovy.transform.Field
def _buildId = ""

@groovy.transform.Field
def _jobName = ""

@groovy.transform.Field
def _branch = ""

@groovy.transform.Field
def _chunkIndex = 0

@groovy.transform.Field
def _client = null

@groovy.transform.Field
def _stageTimings = [:]

def _getClient() {
    if (_client == null) {
        _client = new HttpClient(this)
    }
    return _client
}

def _resolveBranch() {
    def b = env.BRANCH_NAME
           ?: env.GIT_BRANCH
           ?: env.GIT_LOCAL_BRANCH
           ?: env.CHANGE_BRANCH
           ?: "unknown"
    return b.replaceAll(/^origin\//, "")
}

def _initBuildContext() {
    _jobName      = env.JOB_NAME ?: "unknown"
    _branch       = _resolveBranch()
    _buildId      = "${_jobName}/${_branch}_${env.BUILD_NUMBER ?: '0'}"
    _chunkIndex   = 0
    _stageTimings = [:]
}

def _detectBranchType(String branch) {
    def b = branch.toLowerCase()
    if (b.startsWith("pr-") || b.startsWith("pull-")) return "pr"
    if (b =~ /^\d+$/)                                  return "pr"
    if (b in ["main", "master"])                       return "main"
    if (b.startsWith("release"))                       return "release"
    if (b.startsWith("hotfix"))                        return "hotfix"
    if (b.startsWith("develop") || b == "dev")         return "develop"
    if (b.startsWith("feature") || b.startsWith("feat")) return "feature"
    if (b.startsWith("bugfix")  || b.startsWith("fix"))  return "bugfix"
    return "other"
}

// ═══════════════════════════════════════════════════════════════════════════
// BUILD START
// ═══════════════════════════════════════════════════════════════════════════

def buildStart(Map opts = [:]) {
    if (!Config.isEnabled(env)) {
        echo "[DevSentinel] Disabled — skipping"
        return
    }

    _initBuildContext()
    def client = _getClient()

    echo "[DevSentinel] ▶ Build start: ${_buildId} | branch=${_branch} | type=${_detectBranchType(_branch)}"

    client.post("${Config.obj2Url(env)}/webhook/build-start", [
        build_id:    _buildId,
        branch:      _branch,
        branch_type: _detectBranchType(_branch),
        job_name:    _jobName,
        build_url:   env.BUILD_URL ?: "",
        prev_status: currentBuild.previousBuild?.result ?: "none",
    ])

    client.post("${Config.bertUrl(env)}/webhook/build-start", [
        build_id:     _buildId,
        job_name:     _jobName,
        build_number: env.BUILD_NUMBER ?: "0",
        build_url:    env.BUILD_URL ?: "",
        branch:       _branch,
    ])

    echo "[DevSentinel] ✅ All models initialized"
}

// ═══════════════════════════════════════════════════════════════════════════
// WRAP STAGE — sandbox-safe, sans rawBuild
// ═══════════════════════════════════════════════════════════════════════════

def wrapStage(String stageName, Closure body) {
    if (!Config.isEnabled(env)) {
        body()
        return
    }

    def startTime   = System.currentTimeMillis()
    def stageStatus = "SUCCESS"
    def capturedLogs = []

    try {
        body()
    } catch (Exception e) {
        stageStatus = "FAILURE"
        capturedLogs << "ERROR: ${e.message}"
        throw e
    } finally {
        def duration = System.currentTimeMillis() - startTime
        _stageTimings[stageName] = duration
        try {
            _sendStageChunk(stageName, stageStatus, duration, capturedLogs)
        } catch (Exception ex) {
            echo "[DevSentinel] wrapStage warning: ${ex.message}"
        }
    }
}

def _sendStageChunk(String stageName, String stageStatus, long duration, List extraLogs) {
    def client = _getClient()
    _chunkIndex++

    client.post("${Config.obj2Url(env)}/webhook/log-chunk", [
        build_id:     _buildId,
        job_name:     _jobName,
        stage_name:   stageName,
        stage_status: stageStatus,
        duration_ms:  duration,
        chunk_index:  _chunkIndex,
        chunk_text:   extraLogs.join('\n'),
        chunk_lines:  extraLogs,
        total_lines:  extraLogs.size(),
    ])

    client.post("${Config.bertUrl(env)}/webhook/log-chunk", [
        build_id:  _buildId,
        job_name:  _jobName,
        log_chunk: extraLogs.join('\n'),
        stage:     stageName,
    ])

    if (_chunkIndex % 5 == 0) {
        def score = client.get("${Config.obj2Url(env)}/score/${_buildId}")
        if (score?.should_abort) {
            echo "[DevSentinel] ⚠️ ABORT signal — risk=${score.risk_score} action=${score.action}"
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// BUILD END
// ═══════════════════════════════════════════════════════════════════════════

def buildEnd(Map opts = [:]) {
    if (!Config.isEnabled(env)) return

    def client = _getClient()
    def status = currentBuild.result ?: currentBuild.currentResult ?: "SUCCESS"

    echo "[DevSentinel] ◼ Build end: ${_buildId} | status=${status}"

    client.post("${Config.obj2Url(env)}/webhook/build-end", [
        build_id:      _buildId,
        job_name:      _jobName,
        branch:        _branch,
        status:        status,
        duration_ms:   currentBuild.duration ?: 0,
        stage_timings: _stageTimings,
    ])

    client.post("${Config.bertUrl(env)}/webhook/build-end", [
        build_id:    _buildId,
        true_status: status,
        branch:      _branch,
    ])

    echo "[DevSentinel] ✅ Build end processed"
}

// ═══════════════════════════════════════════════════════════════════════════
// SEND LOG — envoi manuel optionnel
// ═══════════════════════════════════════════════════════════════════════════

def sendLog(String logText) {
    if (!Config.isEnabled(env)) return
    _sendStageChunk("manual", "RUNNING", 0, logText.split('\n') as List)
}
