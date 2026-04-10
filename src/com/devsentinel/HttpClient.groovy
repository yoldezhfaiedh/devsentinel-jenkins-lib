package com.devsentinel

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * HttpClient.groovy — DevSentinel AI : HTTP Client avec retry
 * =============================================================
 * Appels HTTP POST/GET vers les serveurs ML Flask.
 * Retry automatique (2 tentatives), timeout configurable.
 * Ne fait JAMAIS échouer le build si un serveur ML est down.
 */
class HttpClient implements Serializable {

    private def script  // référence au pipeline Jenkins (pour echo, httpRequest)

    HttpClient(script) {
        this.script = script
    }

    /**
     * POST JSON vers une URL. Retourne le body parsé ou null si échec.
     */
    Map post(String url, Map payload, int maxRetries = 2) {
        def body = JsonOutput.toJson(payload)
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                def response = script.httpRequest(
                    url:                 url,
                    httpMode:            'POST',
                    contentType:         'APPLICATION_JSON',
                    requestBody:         body,
                    validResponseCodes:  '200:299',
                    consoleLogResponseBody: false,
                    timeout:             Config.readTimeout(),
                    quiet:               true,
                )
                return new JsonSlurper().parseText(response.content)
            } catch (Exception e) {
                if (attempt < maxRetries) {
                    script.echo "[DevSentinel] POST ${url} attempt ${attempt} failed: ${e.message}. Retrying..."
                    script.sleep(time: 1, unit: 'SECONDS')
                } else {
                    script.echo "[DevSentinel] POST ${url} FAILED after ${maxRetries} attempts: ${e.message}"
                    return null
                }
            }
        }
        return null
    }

    /**
     * GET vers une URL. Retourne le body parsé ou null si échec.
     */
    Map get(String url, int maxRetries = 2) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                def response = script.httpRequest(
                    url:                 url,
                    httpMode:            'GET',
                    validResponseCodes:  '200:299',
                    consoleLogResponseBody: false,
                    timeout:             Config.readTimeout(),
                    quiet:               true,
                )
                return new JsonSlurper().parseText(response.content)
            } catch (Exception e) {
                if (attempt < maxRetries) {
                    script.sleep(time: 1, unit: 'SECONDS')
                } else {
                    script.echo "[DevSentinel] GET ${url} FAILED: ${e.message}"
                    return null
                }
            }
        }
        return null
    }
}
