// vars/devsentinel.groovy

def buildStart(Map params) {
    def payload = [
        build_id    : params.buildId,
        job_name    : params.jobName,
        build_number: params.buildNumber,
        build_url   : params.buildUrl,
        branch      : params.branch ?: 'main',
        obj1_prob   : params.obj1Prob ?: 0.5,
        meta__branch_type   : params.branchType ?: 'main',
        meta__trigger_type  : params.triggerType ?: 'manual',
        meta__build_hour    : new Date().getHours(),
        meta__is_weekend    : (new Date().getDay() in [0,6]) ? 1 : 0,
    ]
    _post(env.PHASED_URL + '/webhook/build-start', payload)
    _post(env.BERT_URL   + '/webhook/build-start', payload)
    _post(env.OBJ2_URL   + '/webhook/build-start', payload)
}

def sendChunk(Map params) {
    def payload = [
        build_id    : params.buildId,
        job_name    : params.jobName,
        chunk_index : params.chunkIndex,
        chunk_lines : params.lines,
        total_lines : params.totalLines,
    ]
    // Phased et BERT ont leurs propres pollers Jenkins (progressiveText)
    // → ne pas leur pousser de chunks pour éviter le double comptage.
    // Obj2 n'a pas de poller → c'est le seul qui a besoin du webhook.
    _post(env.OBJ2_URL + '/webhook/log-chunk', payload)
}

def checkAbort(String buildId) {
    def resp = _get(env.OBJ2_URL + "/score/${buildId}")
    if (resp?.should_abort == true) {
        error("🛑 DevSentinel ABORT: score=${resp.risk_score} action=${resp.action}")
    }
    return resp
}

def buildEnd(Map params) {
    def payload = [
        build_id            : params.buildId,
        job_name            : params.jobName,
        status              : params.status,
        true_status         : params.status,
        build_url           : params.buildUrl,
        duration_ms         : params.durationMs,
        // ── Contexte Git pour rollback_queue ──
        branch              : params.branch ?: '',
        repo_url            : params.repoUrl ?: '',
        commit_sha          : params.commitSha ?: '',
        stable_commit_sha   : params.stableCommitSha ?: '',
        build_number        : params.buildNumber ?: '',
    ]
    _post(env.PHASED_URL + '/webhook/build-end', payload, 30)
    _post(env.BERT_URL   + '/webhook/build-end', payload, 30)
    _post(env.OBJ2_URL   + '/webhook/build-end', payload, 30)
}

// ─── helpers privés ─────────────────────────────────────────────────
private def _post(String url, Map body, int timeout = 10) {
    def json = groovy.json.JsonOutput.toJson(body)
    def ts   = System.currentTimeMillis()
    def payloadFile = "ds_payload_${ts}.json"
    def respFile    = "ds_resp_${ts}.txt"
    try {
        writeFile file: payloadFile, text: json
        def rc = sh(
            script: """curl -sS -m ${timeout} \
                       -o ${respFile} \
                       -w '%{http_code}' \
                       -X POST \
                       -H 'Content-Type: application/json' \
                       --data-binary @${payloadFile} \
                       ${url}""",
            returnStdout: true
        ).trim()
        echo "[DevSentinel] POST ${url} → HTTP ${rc}"
        if (!rc.startsWith('2')) {
            def resp = sh(
                script: "cat ${respFile} 2>/dev/null | head -c 500 || echo ''",
                returnStdout: true
            ).trim()
            echo "[DevSentinel] response: ${resp}"
        }
    } catch (e) {
        echo "[DevSentinel] POST ${url} exception: ${e.message}"
    } finally {
        sh(script: "rm -f ${payloadFile} ${respFile} || true", returnStdout: false)
    }
}

private def _get(String url) {
    try {
        def out = sh(script: "curl -sS -m 3 ${url} || echo '{}'", returnStdout: true).trim()
        return readJSON(text: out)
    } catch (e) {
        return [:]
    }
}