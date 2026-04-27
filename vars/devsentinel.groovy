// devsentinel-lib/vars/notifyEnd.groovy (ou équivalent)
def call(Map config = [:]) {
    // Récupère les vars Git de manière robuste
    def gitUrl = env.GIT_URL ?: ''
    def gitCommit = env.GIT_COMMIT ?: ''
    def gitBranch = (env.GIT_BRANCH ?: env.BRANCH_NAME ?: 'main').replaceAll('^origin/', '')
    
    // Fallback : git CLI direct si env vars vides
    if (!gitCommit) {
        try {
            gitCommit = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
        } catch (e) {
            echo "[devsentinel] WARN cannot resolve commit_sha: ${e.message}"
        }
    }
    if (!gitUrl) {
        try {
            gitUrl = sh(returnStdout: true, script: 'git config --get remote.origin.url').trim()
        } catch (e) {
            echo "[devsentinel] WARN cannot resolve repo_url: ${e.message}"
        }
    }
    
    def payload = [
        build_id        : "${env.JOB_NAME}_${env.BUILD_NUMBER}",
        branch          : gitBranch,
        repo_url        : gitUrl,           // ← AJOUT
        commit_sha      : gitCommit,        // ← AJOUT
        downstream_job  : env.JOB_NAME,     // ← AJOUT
        // ... autres champs (score, anomalies, etc.)
    ]
    
    httpRequest(
        url: "${env.DEVSENTINEL_PHASED_URL}/webhook-predictions",
        httpMode: 'POST',
        contentType: 'APPLICATION_JSON',
        requestBody: groovy.json.JsonOutput.toJson(payload)
    )
}