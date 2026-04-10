package com.devsentinel

/**
 * Config.groovy — DevSentinel AI : Configuration centralisée
 * ===========================================================
 * Lit les URLs des serveurs ML depuis les variables d'environnement Jenkins.
 * Fallback sur les valeurs par défaut (host.docker.internal pour Docker).
 *
 * SETUP Jenkins :
 *   Manage Jenkins → System → Global Properties → Environment Variables
 *     DEVSENTINEL_PHASED_URL = http://host.docker.internal:5000
 *     DEVSENTINEL_BERT_URL   = http://host.docker.internal:5002
 *     DEVSENTINEL_OBJ2_URL   = http://host.docker.internal:5004
 *     DEVSENTINEL_ENABLED    = true
 */
class Config implements Serializable {

    // URLs des serveurs ML
    static String phasedUrl(env) { env.DEVSENTINEL_PHASED_URL ?: 'http://host.docker.internal:5000' }
    static String bertUrl(env)   { env.DEVSENTINEL_BERT_URL   ?: 'http://host.docker.internal:5002' }
    static String obj2Url(env)   { env.DEVSENTINEL_OBJ2_URL   ?: 'http://host.docker.internal:5004' }

    // Feature flag global
    static boolean isEnabled(env) {
        def val = env.DEVSENTINEL_ENABLED ?: 'true'
        return val.toString().toLowerCase() == 'true'
    }

    // Timeouts
    static int connectTimeout()  { 5 }   // secondes
    static int readTimeout()     { 15 }  // secondes
    static int chunkSize()       { 50 }  // lignes par chunk
}
