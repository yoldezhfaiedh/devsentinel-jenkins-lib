// vars/devsentinel.groovy

def buildStart(Map params) {
    def payload = [
        build_id    : params.buildId,
        job_name    : params.jobName,
        build_number: params.buildNumber,
        build_url   : params.buildUrl,
        branch      : params.branch ?: 'main',
        obj1_prob   : params.obj1Prob ?: 0.5,
        // meta features v7 pour phased
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
        chunk_lines : params.lines,     // list of strings
        total_lines : params.totalLines,
    ]
    // phased → envoie aussi à obj2 en interne via /internal/phased-score
    _post(env.PHASED_URL + '/webhook/log-chunk', payload)
    // obj2 → appelle bert en interne via _call_bert()
    _post(env.OBJ2_URL   + '/webhook/log-chunk', payload)
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
        build_id   : params.buildId,
        job_name   : params.jobName,
        status     : params.status,       // SUCCESS / FAILURE / UNSTABLE / ABORTED
        true_status: params.status,
        build_url  : params.buildUrl,
        duration_ms: params.durationMs,
    ]
    _post(env.PHASED_URL + '/webhook/build-end', payload)
    _post(env.BERT_URL   + '/webhook/build-end', payload)
    _post(env.OBJ2_URL   + '/webhook/build-end', payload)
}

// ─── helpers privés ─────────────────────────────────────────────────
private def _post(String url, Map body) {
    try {
        def json = groovy.json.JsonOutput.toJson(body)
        sh(script: """curl -sS -m 5 -X POST -H 'Content-Type: application/json' \
                      -d '${json.replace("'", "'\\''")}' ${url} || true""",
           returnStdout: false)
    } catch (e) {
        echo "[DevSentinel] POST ${url} failed: ${e.message}"
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