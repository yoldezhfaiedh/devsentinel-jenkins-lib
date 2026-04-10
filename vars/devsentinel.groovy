#!/usr/bin/env groovy

import com.devsentinel.Config
import com.devsentinel.HttpClient

/**
 * devsentinel.groovy — DevSentinel AI : Shared Library API
 * ==========================================================
 * Fonctions appelées dans les Jenkinsfiles pour intégrer les prédictions ML.
 *
 * Usage dans un Jenkinsfile :
 *
 *   @Library('devsentinel') _
 *
 *   pipeline {
 *     agent any
 *     stages {
 *       stage('Build') {
 *         steps {
 *           script {
 *             devsentinel.buildStart()    // Initialise Obj2 + BERT
 *           }
 *           sh 'mvn clean install'
 *           script {
 *             devsentinel.buildEnd()      // Feedback + post-build analysis
 *           }
 *         }
 *       }
 *     }
 *   }
 *
 * SETUP :
 *   1. Manage Jenkins → System → Global Tool Configuration →
 *      Library 'devsentinel' → Git: https://gitea.local/devsentinel/jenkins-lib.git
 *   2. Manage Jenkins → System → Global Properties → Environment Variables :
 *      DEVSENTINEL_OBJ2_URL, DEVSENTINEL_BERT_URL, DEVSENTINEL_PHASED_URL
 */

// ─── State partagé pendant le build ─────────────────────────────────────────
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

// ─── Initialisation ─────────────────────────────────────────────────────────

def _getClient() {
    if (_client == null) {
        _client = new HttpClient(this)
    }
    return _client
}

def _initBuildContext() {
    _buildId = "${env.JOB_NAME}/${env.BRANCH_NAME ?: 'main'}_${env.BUILD_NUMBER}"
    _jobName = env.JOB_NAME ?: "unknown"
    _branch  = env.BRANCH_NAME ?: env.GIT_BRANCH ?: "unknown"
    _chunkIndex = 0
}

// ═══════════════════════════════════════════════════════════════════════════
// BUILD START — Initialise Obj2 et BERT
// ═══════════════════════════════════════════════════════════════════════════

def buildStart(Map opts = [:]) {
    if (!Config.isEnabled(env)) {
        echo "[DevSentinel] Disabled — skipping"
        return
    }

    _initBuildContext()
    def client = _getClient()

    echo "[DevSentinel] ▶ Build start: ${_buildId}"

    // ── 1. Obj2 : initialiser le tracking ──
    def obj2Payload = [
        build_id:  _buildId,
        branch:    _branch,
        job_name:  _jobName,
    ]
    client.post("${Config.obj2Url(env)}/webhook/build-start", obj2Payload)

    // ── 2. BERT : initialiser le tracking ──
    def bertPayload = [
        build_id:     _buildId,
        job_name:     _jobName,
        build_number: env.BUILD_NUMBER,
        build_url:    env.BUILD_URL ?: "",
    ]
    client.post("${Config.bertUrl(env)}/webhook/build-start", bertPayload)

    echo "[DevSentinel] ✅ All models initialized"
}

// ═══════════════════════════════════════════════════════════════════════════
// LOG CHUNK — Envoie un chunk de log à Obj2 et BERT pendant le build
// ═══════════════════════════════════════════════════════════════════════════

def sendLogChunk(String logText) {
    if (!Config.isEnabled(env)) return

    def client = _getClient()
    def lines = logText.split('\n') as List

    // Découper en chunks de N lignes
    def chunkSize = Config.chunkSize()
    for (int i = 0; i < lines.size(); i += chunkSize) {
        def chunk = lines[i..Math.min(i + chunkSize - 1, lines.size() - 1)]
        _chunkIndex++

        def payload = [
            build_id:    _buildId,
            job_name:    _jobName,
            chunk_index: _chunkIndex,
            chunk_lines: chunk,
            chunk_text:  chunk.join('\n'),
            total_lines: lines.size(),
        ]

        // Envoi vers Obj2 (scoring) et BERT (classification NLP)
        client.post("${Config.obj2Url(env)}/webhook/log-chunk", payload)
        client.post("${Config.bertUrl(env)}/webhook/log-chunk", [
            build_id:  _buildId,
            job_name:  _jobName,
            log_chunk: chunk.join('\n'),
        ])

        // Vérifier le score courant (pull depuis Obj2) toutes les 5 chunks
        if (_chunkIndex % 5 == 0) {
            def score = client.get("${Config.obj2Url(env)}/score/${_buildId}")
            if (score?.should_abort) {
                echo "[DevSentinel] ⚠️ ABORT signal received — risk=${score.risk_score} action=${score.action}"
                // Ne pas error() ici — laisser n8n gérer l'abort via webhook Jenkins
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// BUILD END — Feedback + post-build analysis
// ═══════════════════════════════════════════════════════════════════════════

def buildEnd(Map opts = [:]) {
    if (!Config.isEnabled(env)) return

    def client = _getClient()
    def status = currentBuild.result ?: currentBuild.currentResult ?: "SUCCESS"

    echo "[DevSentinel] ◼ Build end: ${_buildId} status=${status}"

    def endPayload = [
        build_id:    _buildId,
        job_name:    _jobName,
        status:      status,
        duration_ms: currentBuild.duration ?: 0,
    ]

    // ── Obj2 feedback (online learning + score final) ──
    client.post("${Config.obj2Url(env)}/webhook/build-end", endPayload)

    // ── BERT post-build (analysis + RAG update) ──
    client.post("${Config.bertUrl(env)}/webhook/build-end", [
        build_id:    _buildId,
        true_status: status,
    ])

    echo "[DevSentinel] ✅ Build end processed"
}

// ═══════════════════════════════════════════════════════════════════════════
// AUTO LOG CAPTURE — Capture automatique des logs pendant le build
// ═══════════════════════════════════════════════════════════════════════════

def wrapStage(String stageName, Closure body) {
    """
    Wrapper qui capture automatiquement les logs d'un stage.

    Usage :
      devsentinel.wrapStage('Build') {
          sh 'mvn clean install'
      }
    """
    if (!Config.isEnabled(env)) {
        body()
        return
    }

    def logBefore = currentBuild.rawBuild?.getLog(9999)?.size() ?: 0

    try {
        body()
    } finally {
        try {
            def allLines = currentBuild.rawBuild?.getLog(9999) ?: []
            def newLines = allLines.drop(logBefore)
            if (newLines.size() > 0) {
                sendLogChunk(newLines.join('\n'))
            }
        } catch (Exception e) {
            echo "[DevSentinel] Log capture warning: ${e.message}"
        }
    }
}

// ─── Helpers ────────────────────────────────────────────────────────────────

def _detectBranchType(String branch) {
    def b = branch.toLowerCase()
    if (b.startsWith("pr-") || b.startsWith("PR-")) return "pr"
    if (b in ["main", "master"]) return "main"
    if (b.startsWith("release")) return "release"
    if (b.startsWith("hotfix")) return "hotfix"
    if (b.startsWith("develop") || b == "dev") return "develop"
    if (b.startsWith("feature") || b.startsWith("feat")) return "feature"
    if (b.startsWith("bugfix") || b.startsWith("fix")) return "bugfix"
    return "other"
}
