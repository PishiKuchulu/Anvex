// libanvex-vec JNI bridge — P7 (F6): per-project chunk embeddings in a
// private vectors.db (SQLite 3.50.4 amalgamation + sqlite-vec 0.1.9,
// statically linked). Design notes:
// - One vec0 table + one meta table PER PROJECT (vec_<sanitized>,
//   meta_<sanitized>) so project deletion is a DROP and queries never
//   need metadata-column filtering. A shared file_hashes table drives
//   incremental indexing (unchanged files are never re-embedded).
// - Embeddings are L2-normalized float32[384] on the Kotlin side, so
//   vec0's L2 distance ranks identically to cosine.
// - Open-per-call: no handle state; sqlite3_auto_extension registers
//   vec0 once per process.
// Line protocol for query results (tab-separated, text escaped):
//   chunkId \t path \t startLine \t endLine \t distance \t text
#include <jni.h>

#include <sqlite3.h>
#include <sqlite-vec.h>

#include <cstring>
#include <string>
#include <vector>

extern "C" int sqlite3_vec_init(sqlite3 *db, char **pzErrMsg,
                                const sqlite3_api_routines *pApi);

namespace {

constexpr int kDim = 384;
bool g_vecRegistered = false;

sqlite3 *openDb(const char *path) {
    if (!g_vecRegistered) {
        sqlite3_auto_extension(
            reinterpret_cast<void (*)(void)>(sqlite3_vec_init));
        g_vecRegistered = true;
    }
    sqlite3 *db = nullptr;
    if (sqlite3_open(path, &db) != SQLITE_OK) {
        if (db) sqlite3_close(db);
        return nullptr;
    }
    char *err = nullptr;
    sqlite3_exec(db, "PRAGMA journal_mode=WAL;", nullptr, nullptr, &err);
    if (err) sqlite3_free(err);
    return db;
}

std::string jstr(JNIEnv *env, jstring s) {
    if (s == nullptr) return "";
    const char *p = env->GetStringUTFChars(s, nullptr);
    std::string out = p != nullptr ? p : "";
    if (p != nullptr) env->ReleaseStringUTFChars(s, p);
    return out;
}

std::string tableSuffix(JNIEnv *env, jstring project) {
    std::string raw = jstr(env, project);
    std::string out;
    for (char ch : raw) {
        const bool ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') ||
                        (ch >= '0' && ch <= '9') || ch == '_';
        out += (ok ? ch : '_');
    }
    return out.empty() ? std::string("default") : out;
}

bool execSql(sqlite3 *db, const std::string &sql) {
    char *err = nullptr;
    const bool ok = sqlite3_exec(db, sql.c_str(), nullptr, nullptr, &err) == SQLITE_OK;
    if (err) sqlite3_free(err);
    return ok;
}

bool ensureSchema(sqlite3 *db, const std::string &sfx) {
    return execSql(db,
                   "CREATE TABLE IF NOT EXISTS file_hashes("
                   "project TEXT, path TEXT, hash TEXT, PRIMARY KEY(project, path));"
                   "CREATE VIRTUAL TABLE IF NOT EXISTS vec_" +
                       sfx + " USING vec0(embedding float[" +
                       std::to_string(kDim) +
                       "]);"
                       "CREATE TABLE IF NOT EXISTS meta_" +
                       sfx +
                       "(rowid INTEGER PRIMARY KEY, chunk_id TEXT UNIQUE, "
                       "path TEXT, start_line INTEGER, end_line INTEGER, text TEXT);");
}

std::string escapeField(const std::string &s) {
    std::string out;
    out.reserve(s.size());
    for (char ch : s) {
        switch (ch) {
            case '\\': out += "\\\\"; break;
            case '\t': out += "\\t"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            default: out += ch;
        }
    }
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_anvex_core_android_runtime_rag_VecBridge_nativeVecVersion(JNIEnv *env, jclass) {
    std::string v = std::string("sqlite ") + sqlite3_libversion() +
                    " / " + SQLITE_VEC_VERSION;
    return env->NewStringUTF(v.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_anvex_core_android_runtime_rag_VecBridge_nativeFileHash(
    JNIEnv *env, jclass, jstring dbPath, jstring project, jstring path) {
    sqlite3 *db = openDb(jstr(env, dbPath).c_str());
    if (db == nullptr) return nullptr;
    execSql(db, "CREATE TABLE IF NOT EXISTS file_hashes("
                "project TEXT, path TEXT, hash TEXT, PRIMARY KEY(project, path));");
    sqlite3_stmt *st = nullptr;
    jstring out = nullptr;
    if (sqlite3_prepare_v2(db,
                           "SELECT hash FROM file_hashes WHERE project=? AND path=?",
                           -1, &st, nullptr) == SQLITE_OK) {
        const std::string p = jstr(env, project);
        const std::string pa = jstr(env, path);
        sqlite3_bind_text(st, 1, p.c_str(), -1, SQLITE_TRANSIENT);
        sqlite3_bind_text(st, 2, pa.c_str(), -1, SQLITE_TRANSIENT);
        if (sqlite3_step(st) == SQLITE_ROW) {
            const unsigned char *h = sqlite3_column_text(st, 0);
            if (h != nullptr) out = env->NewStringUTF(reinterpret_cast<const char *>(h));
        }
    }
    if (st) sqlite3_finalize(st);
    sqlite3_close(db);
    return out;
}

// Replaces all chunks of one file atomically. embeddings is the
// concatenation of count float32[kDim] little-endian vectors.
// Returns the number of chunks stored, or -1 on failure.
JNIEXPORT jint JNICALL
Java_com_anvex_core_android_runtime_rag_VecBridge_nativeReplaceFile(
    JNIEnv *env, jclass, jstring dbPath, jstring project, jstring path,
    jstring fileHash, jobjectArray chunkIds, jintArray startLines,
    jintArray endLines, jobjectArray texts, jbyteArray embeddings) {
    const std::string proj = jstr(env, project);
    const std::string pa = jstr(env, path);
    const std::string hash = jstr(env, fileHash);
    const std::string sfx = tableSuffix(env, project);
    const jsize count = chunkIds != nullptr ? env->GetArrayLength(chunkIds) : 0;
    const jsize embLen = embeddings != nullptr ? env->GetArrayLength(embeddings) : 0;
    if (embLen < count * kDim * 4) return -1;
    std::vector<jbyte> emb(static_cast<size_t>(embLen));
    if (embLen > 0) env->GetByteArrayRegion(embeddings, 0, embLen, emb.data());
    std::vector<jint> starts(static_cast<size_t>(count)), ends(static_cast<size_t>(count));
    if (count > 0) {
        env->GetIntArrayRegion(startLines, 0, count, starts.data());
        env->GetIntArrayRegion(endLines, 0, count, ends.data());
    }
    sqlite3 *db = openDb(jstr(env, dbPath).c_str());
    if (db == nullptr) return -1;
    jint result = -1;
    if (ensureSchema(db, sfx) && execSql(db, "BEGIN IMMEDIATE;")) {
        bool ok = true;
        // Delete existing rows for this path from both tables.
        {
            sqlite3_stmt *sel = nullptr;
            std::vector<long long> rowids;
            if (sqlite3_prepare_v2(db, ("SELECT rowid FROM meta_" + sfx + " WHERE path=?").c_str(),
                                   -1, &sel, nullptr) == SQLITE_OK) {
                sqlite3_bind_text(sel, 1, pa.c_str(), -1, SQLITE_TRANSIENT);
                while (sqlite3_step(sel) == SQLITE_ROW) rowids.push_back(sqlite3_column_int64(sel, 0));
            }
            if (sel) sqlite3_finalize(sel);
            sqlite3_stmt *del = nullptr;
            if (sqlite3_prepare_v2(db, ("DELETE FROM vec_" + sfx + " WHERE rowid=?").c_str(),
                                   -1, &del, nullptr) == SQLITE_OK) {
                for (long long id : rowids) {
                    sqlite3_bind_int64(del, 1, id);
                    sqlite3_step(del);
                    sqlite3_reset(del);
                }
            }
            if (del) sqlite3_finalize(del);
            sqlite3_stmt *delMeta = nullptr;
            if (sqlite3_prepare_v2(db, ("DELETE FROM meta_" + sfx + " WHERE path=?").c_str(),
                                   -1, &delMeta, nullptr) == SQLITE_OK) {
                sqlite3_bind_text(delMeta, 1, pa.c_str(), -1, SQLITE_TRANSIENT);
                ok = sqlite3_step(delMeta) == SQLITE_DONE && ok;
            } else {
                ok = false;
            }
            if (delMeta) sqlite3_finalize(delMeta);
        }
        sqlite3_stmt *insVec = nullptr;
        sqlite3_stmt *insMeta = nullptr;
        if (ok && sqlite3_prepare_v2(db, ("INSERT INTO vec_" + sfx + "(embedding) VALUES(?)").c_str(),
                                     -1, &insVec, nullptr) == SQLITE_OK &&
            sqlite3_prepare_v2(db, ("INSERT INTO meta_" + sfx +
                                    "(rowid, chunk_id, path, start_line, end_line, text) VALUES(?,?,?,?,?,?)")
                                       .c_str(),
                               -1, &insMeta, nullptr) == SQLITE_OK) {
            for (jsize i = 0; i < count && ok; i++) {
                sqlite3_bind_blob(insVec, 1, emb.data() + static_cast<size_t>(i) * kDim * 4,
                                  kDim * 4, SQLITE_TRANSIENT);
                ok = sqlite3_step(insVec) == SQLITE_DONE;
                sqlite3_reset(insVec);
                if (!ok) break;
                const long long rowid = sqlite3_last_insert_rowid(db);
                auto *cidEl = static_cast<jstring>(env->GetObjectArrayElement(chunkIds, i));
                auto *txtEl = static_cast<jstring>(env->GetObjectArrayElement(texts, i));
                const std::string cid = jstr(env, cidEl);
                const std::string txt = jstr(env, txtEl);
                if (cidEl) env->DeleteLocalRef(cidEl);
                if (txtEl) env->DeleteLocalRef(txtEl);
                sqlite3_bind_int64(insMeta, 1, rowid);
                sqlite3_bind_text(insMeta, 2, cid.c_str(), -1, SQLITE_TRANSIENT);
                sqlite3_bind_text(insMeta, 3, pa.c_str(), -1, SQLITE_TRANSIENT);
                sqlite3_bind_int(insMeta, 4, starts[static_cast<size_t>(i)]);
                sqlite3_bind_int(insMeta, 5, ends[static_cast<size_t>(i)]);
                sqlite3_bind_text(insMeta, 6, txt.c_str(), -1, SQLITE_TRANSIENT);
                ok = sqlite3_step(insMeta) == SQLITE_DONE;
                sqlite3_reset(insMeta);
            }
        } else {
            ok = false;
        }
        if (insVec) sqlite3_finalize(insVec);
        if (insMeta) sqlite3_finalize(insMeta);
        if (ok) {
            sqlite3_stmt *up = nullptr;
            if (sqlite3_prepare_v2(db,
                                   "INSERT INTO file_hashes(project, path, hash) VALUES(?,?,?) "
                                   "ON CONFLICT(project, path) DO UPDATE SET hash=excluded.hash",
                                   -1, &up, nullptr) == SQLITE_OK) {
                sqlite3_bind_text(up, 1, proj.c_str(), -1, SQLITE_TRANSIENT);
                sqlite3_bind_text(up, 2, pa.c_str(), -1, SQLITE_TRANSIENT);
                sqlite3_bind_text(up, 3, hash.c_str(), -1, SQLITE_TRANSIENT);
                ok = sqlite3_step(up) == SQLITE_DONE;
            } else {
                ok = false;
            }
            if (up) sqlite3_finalize(up);
        }
        if (ok && execSql(db, "COMMIT;")) {
            result = count;
        } else {
            execSql(db, "ROLLBACK;");
        }
    }
    sqlite3_close(db);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_anvex_core_android_runtime_rag_VecBridge_nativeQuery(
    JNIEnv *env, jclass, jstring dbPath, jstring project, jbyteArray query,
    jint k) {
    if (query == nullptr || env->GetArrayLength(query) < kDim * 4) return nullptr;
    std::vector<jbyte> q(static_cast<size_t>(kDim * 4));
    env->GetByteArrayRegion(query, 0, kDim * 4, q.data());
    const std::string sfx = tableSuffix(env, project);
    sqlite3 *db = openDb(jstr(env, dbPath).c_str());
    if (db == nullptr) return nullptr;
    std::string out;
    if (ensureSchema(db, sfx)) {
        sqlite3_stmt *st = nullptr;
        const std::string sql =
            "SELECT m.chunk_id, m.path, m.start_line, m.end_line, v.distance, m.text "
            "FROM vec_" + sfx + " v JOIN meta_" + sfx +
            " m ON m.rowid = v.rowid WHERE v.embedding MATCH ? "
            "ORDER BY v.distance LIMIT ?";
        if (sqlite3_prepare_v2(db, sql.c_str(), -1, &st, nullptr) == SQLITE_OK) {
            sqlite3_bind_blob(st, 1, q.data(), kDim * 4, SQLITE_TRANSIENT);
            sqlite3_bind_int(st, 2, k);
            bool first = true;
            while (sqlite3_step(st) == SQLITE_ROW) {
                if (!first) out += '\n';
                first = false;
                auto col = [&](int i) {
                    const unsigned char *t = sqlite3_column_text(st, i);
                    return t != nullptr ? std::string(reinterpret_cast<const char *>(t)) : std::string();
                };
                char dist[32];
                std::snprintf(dist, sizeof(dist), "%.4f", sqlite3_column_double(st, 4));
                out += escapeField(col(0)) + '\t' + escapeField(col(1)) + '\t' +
                       std::to_string(sqlite3_column_int(st, 2)) + '\t' +
                       std::to_string(sqlite3_column_int(st, 3)) + '\t' + dist +
                       '\t' + escapeField(col(5));
            }
        }
        if (st) sqlite3_finalize(st);
    }
    sqlite3_close(db);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jint JNICALL
Java_com_anvex_core_android_runtime_rag_VecBridge_nativeCount(
    JNIEnv *env, jclass, jstring dbPath, jstring project) {
    const std::string sfx = tableSuffix(env, project);
    sqlite3 *db = openDb(jstr(env, dbPath).c_str());
    if (db == nullptr) return 0;
    jint n = 0;
    if (ensureSchema(db, sfx)) {
        sqlite3_stmt *st = nullptr;
        if (sqlite3_prepare_v2(db, ("SELECT count(*) FROM meta_" + sfx).c_str(),
                               -1, &st, nullptr) == SQLITE_OK &&
            sqlite3_step(st) == SQLITE_ROW) {
            n = sqlite3_column_int(st, 0);
        }
        if (st) sqlite3_finalize(st);
    }
    sqlite3_close(db);
    return n;
}

JNIEXPORT void JNICALL
Java_com_anvex_core_android_runtime_rag_VecBridge_nativeClearProject(
    JNIEnv *env, jclass, jstring dbPath, jstring project) {
    const std::string sfx = tableSuffix(env, project);
    const std::string proj = jstr(env, project);
    sqlite3 *db = openDb(jstr(env, dbPath).c_str());
    if (db == nullptr) return;
    execSql(db, "DROP TABLE IF EXISTS vec_" + sfx + ";");
    execSql(db, "DROP TABLE IF EXISTS meta_" + sfx + ";");
    sqlite3_stmt *st = nullptr;
    if (sqlite3_prepare_v2(db, "DELETE FROM file_hashes WHERE project=?", -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(st, 1, proj.c_str(), -1, SQLITE_TRANSIENT);
        sqlite3_step(st);
    }
    if (st) sqlite3_finalize(st);
    sqlite3_close(db);
}

}  // extern "C"
