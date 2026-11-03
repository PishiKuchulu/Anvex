// Anvex Git bridge (F1, P3) — libgit2 1.9.7 statically linked into
// libanvex-git.so. Transport only: every call opens the repository, does
// one operation, and closes it again (no native handles outlive a call, so
// a crash here cannot corrupt a cached repository object). Errors come
// back as "ERR: <libgit2 message>" strings; typed parsing lives in Kotlin.
//
// Scope: LOCAL operations (init/status/add/commit/log/branches/checkout/
// merge/conflicts/resolve/abort, workdir diff). Network transports are
// deliberately NOT compiled in (USE_HTTPS/USE_SSH off): clone/fetch/push
// stay on the app's existing GitHub paths until a TLS backend is chosen.

#include <jni.h>
#include <git2.h>
#include <string>
#include <vector>
#include <mutex>

namespace {

std::once_flag g_initOnce;

void EnsureInit() {
    std::call_once(g_initOnce, [] { git_libgit2_init(); });
}

std::string JStr(JNIEnv *env, jstring s) {
    if (s == nullptr) return "";
    const char *utf = env->GetStringUTFChars(s, nullptr);
    if (utf == nullptr) return "";
    std::string out(utf);
    env->ReleaseStringUTFChars(s, utf);
    return out;
}

jstring JRet(JNIEnv *env, const std::string &s) {
    return env->NewStringUTF(s.c_str());
}

std::string Err(const char *what) {
    const git_error *e = git_error_last();
    std::string msg = (e && e->message) ? e->message : "unknown error";
    return std::string("ERR: ") + what + ": " + msg;
}

// Sanitize a field for the tab-separated wire format.
std::string Field(const std::string &s) {
    std::string out = s;
    for (auto &c : out) if (c == '\t' || c == '\n' || c == '\r') c = ' ';
    return out;
}

struct Repo {
    git_repository *repo = nullptr;
    explicit Repo(const std::string &path) { git_repository_open(&repo, path.c_str()); }
    ~Repo() { if (repo) git_repository_free(repo); }
    bool ok() const { return repo != nullptr; }
};

std::string OidStr(const git_oid *oid) {
    char buf[GIT_OID_MAX_HEXSIZE + 1];
    git_oid_tostr(buf, sizeof(buf), oid);
    return buf;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_anvex_core_android_runtime_git_GitBridge_nativeGitVersion(
        JNIEnv *env, jobject /* this */) {
    return JRet(env, LIBGIT2_VERSION);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_anvex_core_android_runtime_git_GitBridge_nativeIsRepo(
        JNIEnv *env, jobject /* this */, jstring path) {
    EnsureInit();
    Repo r(JStr(env, path));
    return r.ok() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_anvex_core_android_runtime_git_GitBridge_nativeInit(
        JNIEnv *env, jobject /* this */, jstring path) {
    EnsureInit();
    git_repository *repo = nullptr;
    if (git_repository_init(&repo, JStr(env, path).c_str(), 0) < 0) return JRet(env, Err("init"));
    git_repository_free(repo);
    return JRet(env, "OK");
}

// Porcelain status: one line per entry "XY<tab>path" (X=index, Y=workdir;
// chars from . M A D R ?), plus "!H" first line when HEAD is unborn.
extern "C" JNIEXPORT jstring JNICALL
Java_com_anvex_core_android_runtime_git_GitBridge_nativeStatus(
        JNIEnv *env, jobject /* this */, jstring path) {
    EnsureInit();
    Repo r(JStr(env, path));
    if (!r.ok()) return JRet(env, Err("open"));
    git_status_options opts = GIT_STATUS_OPTIONS_INIT;
    opts.show = GIT_STATUS_SHOW_INDEX_AND_WORKDIR;
    opts.flags = GIT_STATUS_OPT_INCLUDE_UNTRACKED | GIT_STATUS_OPT_RECURSE_UNTRACKED_DIRS;
    git_status_list *list = nullptr;
    if (git_status_list_new(&list, r.repo, &opts) < 0) return JRet(env, Err("status"));
    std::string out;
    size_t n = git_status_list_entrycount(list);
    for (size_t i = 0; i < n; i++) {
        const git_status_entry *e = git_status_byindex(list, i);
        char ix = '.', wt = '.';
        unsigned s = e->status;
        if (s & GIT_STATUS_CONFLICTED) { ix = 'U'; wt = 'U'; }
        else if (s & GIT_STATUS_INDEX_NEW) ix = 'A';
        else if (s & GIT_STATUS_INDEX_MODIFIED) ix = 'M';
        else if (s & GIT_STATUS_INDEX_DELETED) ix = 'D';
        else if (s & GIT_STATUS_INDEX_RENAMED) ix = 'R';
        if (s & GIT_STATUS_WT_NEW) wt = '?';
        else if (s & GIT_STATUS_WT_MODIFIED) wt = 'M';
        else if (s & GIT_STATUS_WT_DELETED) wt = 'D';
        else if (s & GIT_STATUS_WT_RENAMED) wt = 'R';
        const char *p = e->head_to_index ? e->head_to_index->new_file.path
                                        : (e->index_to_workdir ? e->index_to_workdir->new_file.path : "");
        out += ix; out += wt; out += '\t'; out += (p ? p : ""); out += '\n';
    }
    git_status_list_free(list);
    return JRet(env, out);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_anvex_core_android_runtime_git_GitBridge_nativeAdd(
        JNIEnv *env, jobject /* this */, jstring path, jstring pathspec) {
    EnsureInit();
    Repo r(JStr(env, path));
    if (!r.ok()) return JRet(env, Err("open"));
    git_index *index = nullptr;
    if (git_repository_index(&index, r.repo) < 0) return JRet(env, Err("index"));
    std::string spec = JStr(env, pathspec);
    int rc;
    if (spec == "." || spec.empty()) {
        rc = git_index_add_all(index, nullptr, 0, nullptr, nullptr);
        if (rc >= 0) rc = git_index_update_all(index, nullptr, nullptr, nullptr);
    } else {
        rc = git_index_add_bypath(index, spec.c_str());
    }
    if (rc >= 0) rc = git_index_write(index);
    git_index_free(index);
    if (rc < 0) return JRet(env, Err("add"));
    return JRet(env, "OK");
}

// Creates a commit from the current index. Returns the new commit oid.
extern "C" JNIEXPORT jstring JNICALL
Java_com_anvex_core_android_runtime_git_GitBridge_nativeCommit(
        JNIEnv *env, jobject /* this */, jstring path, jstring message,
        jstring authorName, jstring authorEmail) {
    EnsureInit();
    Repo r(JStr(env, path));
    if (!r.ok()) return JRet(env, Err("open"));
    git_signature *sig = nullptr;
    std::string name = JStr(env, authorName), email = JStr(env, authorEmail);
    if (git_signature_now(&sig, name.c_str(), email.c_str()) < 0) return JRet(env, Err("signature"));
    git_index *index = nullptr;
    git_repository_index(&index, r.repo);
    git_oid treeOid;
    git_index_write_tree(&treeOid, index);
    git_index_free(index);
    git_tree *tree = nullptr;
    git_tree_lookup(&tree, r.repo, &treeOid);

    // Parent = HEAD commit when it exists (unborn HEAD => root commit).
    git_commit *parent = nullptr;
    git_oid headOid;
    bool hasParent = git_reference_name_to_id(&headOid, r.repo, "HEAD") == 0;
    if (hasParent) git_commit_lookup(&parent, r.repo, &headOid);

    git_oid commitOid;
    const git_commit *parents[1] = { parent };
    int rc = git_commit_create(&commitOid, r.repo, "HEAD", sig, sig, "UTF-8",
                               JStr(env, message).c_str(), tree,
                               hasParent ? 1 : 0, hasParent ? parents : nullptr);
    git_tree_free(tree);
    if (parent) git_commit_free(parent);
    git_signature_free(sig);
    if (rc < 0) return JRet(env, Err("commit"));
    return JRet(env, OidStr(&commitOid));
}

// Log lines: "oid<tab>author name<tab>author email<tab>epoch<tab>subject".
extern "C" JNIEXPORT jstring JNICALL
Java_com_anvex_core_android_runtime_git_GitBridge_nativeLog(
        JNIEnv *env, jobject /* this */, jstring path, jint limit) {
    EnsureInit();
    Repo r(JStr(env, path));
    if (!r.ok()) return JRet(env, Err("open"));
    git_revwalk *walk = nullptr;
    git_revwalk_new(&walk, r.repo);
    git_revwalk_sorting(walk, GIT_SORT_TIME);
    if (git_revwalk_push_head(walk) < 0) { git_revwalk_free(walk); return JRet(env, ""); }
    std::string out;
    git_oid oid;
    int count = 0;
    while (git_revwalk_next(&oid, walk) == 0 && count < limit) {
        git_commit *c = nullptr;
        git_commit_lookup(&c, r.repo, &oid);
        if (!c) continue;
        const git_signature *a = git_commit_author(c);
        const char *summary = git_commit_summary(c);
        out += OidStr(&oid); out += '\t';
        out += Field(a ? a->name : ""); out += '\t';
        out += Field(a ? a->email : ""); out += '\t';
        out += std::to_string(a ? (long long) a->when.time : 0); out += '\t';
        out += Field(summary ? summary : ""); out += '\n';
        git_commit_free(c);
        count++;
    }
    git_revwalk_free(walk);
    return JRet(env, out);
}

// Branch lines: "name<tab>1-if-current<tab>oid".
extern "C" JNIEXPORT jstring JNICALL
Java_com_anvex_core_android_runtime_git_GitBridge_nativeBranches(
        JNIEnv *env, jobject /* this */, jstring path) {
    EnsureInit();
    Repo r(JStr(env, path));
    if (!r.ok()) return JRet(env, Err("open"));
    git_branch_iterator *it = nullptr;
    if (git_branch_iterator_new(&it, r.repo, GIT_BRANCH_LOCAL) < 0) return JRet(env, Err("branches"));
    std::string out;
    git_reference *ref = nullptr;
    git_branch_t type;
    while (git_branch_next(&ref, &type, it) == 0) {
        const char *name = nullptr;
        git_branch_name(&name, ref);
        git_reference *head = nullptr;
        git_repository_head(&head, r.repo);
        bool current = head && git_reference_cmp(ref, head) == 0;
        if (head) git_reference_free(head);
        const git_oid *target = git_reference_target(ref);
        out += Field(name ? name : ""); out += '\t';
        out += current ? '1' : '0'; out += '\t';
        out += target ? OidStr(target) : ""; out += '\n';
        git_reference_free(ref);
    }
    git_branch_iterator_free(it);
    return JRet(env, out);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_anvex_core_android_runtime_git_GitBridge_nativeCreateBranch(
        JNIEnv *env, jobject /* this */, jstring path, jstring name) {
    EnsureInit();
    Repo r(JStr(env, path));
    if (!r.ok()) return JRet(env, Err("open"));
    git_oid headOid;
    if (git_reference_name_to_id(&headOid, r.repo, "HEAD") < 0) return JRet(env, Err("head"));
    git_commit *head = nullptr;
    git_commit_lookup(&head, r.repo, &headOid);
    git_reference *branch = nullptr;
    int rc = git_branch_create(&branch, r.repo, JStr(env, name).c_str(), head, 0);
    if (branch) git_reference_free(branch);
    if (head) git_commit_free(head);
    if (rc < 0) return JRet(env, Err("branch_create"));
    return JRet(env, "OK");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_anvex_core_android_runtime_git_GitBridge_nativeCheckout(
        JNIEnv *env, jobject /* this */, jstring path, jstring name) {
    EnsureInit();
    Repo r(JStr(env, path));
    if (!r.ok()) return JRet(env, Err("open"));
    git_reference *branch = nullptr;
    if (git_branch_lookup(&branch, r.repo, JStr(env, name).c_str(), GIT_BRANCH_LOCAL) < 0)
        return JRet(env, Err("branch_lookup"));
    const git_oid *target = git_reference_target(branch);
    git_object *obj = nullptr;
    git_object_lookup(&obj, r.repo, target, GIT_OBJECT_COMMIT);
    git_checkout_options opts = GIT_CHECKOUT_OPTIONS_INIT;
    opts.checkout_strategy = GIT_CHECKOUT_SAFE;
    int rc = git_checkout_tree(r.repo, obj, &opts);
    if (rc >= 0) rc = git_repository_set_head(r.repo, git_reference_name(branch));
    if (obj) git_object_free(obj);
    git_reference_free(branch);
    if (rc < 0) return JRet(env, Err("checkout"));
    return JRet(env, "OK");
}

// Merge [branch] into HEAD. Returns FASTFORWARD | UP_TO_DATE | CLEAN | CONFLICT.
extern "C" JNIEXPORT jstring JNICALL
Java_com_anvex_core_android_runtime_git_GitBridge_nativeMerge(
        JNIEnv *env, jobject /* this */, jstring path, jstring name) {
    EnsureInit();
    Repo r(JStr(env, path));
    if (!r.ok()) return JRet(env, Err("open"));
    git_reference *branch = nullptr;
    if (git_branch_lookup(&branch, r.repo, JStr(env, name).c_str(), GIT_BRANCH_LOCAL) < 0)
        return JRet(env, Err("branch_lookup"));
    git_annotated_commit *other = nullptr;
    git_annotated_commit_from_ref(&other, r.repo, branch);

    git_merge_analysis_t analysis;
    git_merge_preference_t pref;
    const git_annotated_commit *arr[1] = { other };
    int rc = git_merge_analysis(&analysis, &pref, r.repo, arr, 1);
    std::string result;
    if (rc < 0) {
        result = Err("merge_analysis");
    } else if (analysis & GIT_MERGE_ANALYSIS_UP_TO_DATE) {
        result = "UP_TO_DATE";
    } else if (analysis & GIT_MERGE_ANALYSIS_FASTFORWARD) {
        // Move HEAD to the other commit and check it out.
        const git_oid *target = git_annotated_commit_id(other);
        git_object *obj = nullptr;
        git_object_lookup(&obj, r.repo, target, GIT_OBJECT_COMMIT);
        git_checkout_options opts = GIT_CHECKOUT_OPTIONS_INIT;
        opts.checkout_strategy = GIT_CHECKOUT_SAFE;
        git_checkout_tree(r.repo, obj, &opts);
        if (obj) git_object_free(obj);
        git_reference *headRef = nullptr;
        git_repository_head(&headRef, r.repo);
        if (headRef) {
            git_reference *moved = nullptr;
            git_reference_set_target(&moved, headRef, target, "merge: fast-forward");
            if (moved) git_reference_free(moved);
            git_reference_free(headRef);
        }
        result = "FASTFORWARD";
    } else {
        git_merge_options mopts = GIT_MERGE_OPTIONS_INIT;
        git_checkout_options copts = GIT_CHECKOUT_OPTIONS_INIT;
        copts.checkout_strategy = GIT_CHECKOUT_SAFE | GIT_CHECKOUT_ALLOW_CONFLICTS;
        rc = git_merge(r.repo, arr, 1, &mopts, &copts);
        if (rc < 0) {
            result = Err("merge");
        } else {
            git_index *index = nullptr;
            git_repository_index(&index, r.repo);
            result = (index && git_index_has_conflicts(index)) ? "CONFLICT" : "CLEAN";
            if (index) git_index_free(index);
        }
    }
    git_annotated_commit_free(other);
    git_reference_free(branch);
    return JRet(env, result);
}

// Conflicted paths, one per line.
extern "C" JNIEXPORT jstring JNICALL
Java_com_anvex_core_android_runtime_git_GitBridge_nativeConflicts(
        JNIEnv *env, jobject /* this */, jstring path) {
    EnsureInit();
    Repo r(JStr(env, path));
    if (!r.ok()) return JRet(env, Err("open"));
    git_index *index = nullptr;
    git_repository_index(&index, r.repo);
    std::string out;
    if (index && git_index_has_conflicts(index)) {
        git_index_conflict_iterator *it = nullptr;
        git_index_conflict_iterator_new(&it, index);
        const git_index_entry *anc = nullptr, *our = nullptr, *their = nullptr;
        while (git_index_conflict_next(&anc, &our, &their, it) == 0) {
            const git_index_entry *any = our ? our : (their ? their : anc);
            if (any) { out += any->path; out += '\n'; }
        }
        git_index_conflict_iterator_free(it);
    }
    if (index) git_index_free(index);
    return JRet(env, out);
}

// Stage contents for one conflicted path: [ancestor, ours, theirs]
// (empty string for a missing stage — e.g. delete/modify conflicts).
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_anvex_core_android_runtime_git_GitBridge_nativeConflictStages(
        JNIEnv *env, jobject /* this */, jstring path, jstring conflictPath) {
    EnsureInit();
    jobjectArray out = env->NewObjectArray(3, env->FindClass("java/lang/String"), nullptr);
    Repo r(JStr(env, path));
    if (!r.ok()) return out;
    git_index *index = nullptr;
    git_repository_index(&index, r.repo);
    const git_index_entry *anc = nullptr, *our = nullptr, *their = nullptr;
    std::string cp = JStr(env, conflictPath);
    git_index_conflict_get(&anc, &our, &their, index, cp.c_str());
    const git_index_entry *stages[3] = {anc, our, their};
    for (int i = 0; i < 3; i++) {
        std::string content;
        if (stages[i]) {
            git_blob *blob = nullptr;
            if (git_blob_lookup(&blob, r.repo, &stages[i]->id) == 0) {
                content.assign((const char *) git_blob_rawcontent(blob),
                               (size_t) git_blob_rawsize(blob));
                git_blob_free(blob);
            }
        }
        env->SetObjectArrayElement(out, i, JRet(env, content));
    }
    if (index) git_index_free(index);
    return out;
}

// Writes the resolved content to the workdir file and stages it (which is
// what clears the conflict entry for that path).
extern "C" JNIEXPORT jstring JNICALL
Java_com_anvex_core_android_runtime_git_GitBridge_nativeResolveFile(
        JNIEnv *env, jobject /* this */, jstring path, jstring conflictPath, jstring content) {
    EnsureInit();
    std::string root = JStr(env, path);
    std::string rel = JStr(env, conflictPath);
    std::string full = root + "/" + rel;
    FILE *f = fopen(full.c_str(), "wb");
    if (!f) return JRet(env, "ERR: resolve: cannot open file for writing");
    std::string data = JStr(env, content);
    fwrite(data.data(), 1, data.size(), f);
    fclose(f);
    Repo r(root);
    if (!r.ok()) return JRet(env, Err("open"));
    git_index *index = nullptr;
    git_repository_index(&index, r.repo);
    int rc = git_index_add_bypath(index, rel.c_str());
    if (rc >= 0) rc = git_index_write(index);
    git_index_free(index);
    if (rc < 0) return JRet(env, Err("resolve_add"));
    return JRet(env, "OK");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_anvex_core_android_runtime_git_GitBridge_nativeAbortMerge(
        JNIEnv *env, jobject /* this */, jstring path) {
    EnsureInit();
    Repo r(JStr(env, path));
    if (!r.ok()) return JRet(env, Err("open"));
    git_oid headOid;
    if (git_reference_name_to_id(&headOid, r.repo, "HEAD") < 0) return JRet(env, Err("head"));
    git_object *headObj = nullptr;
    git_object_lookup(&headObj, r.repo, &headOid, GIT_OBJECT_COMMIT);
    int rc = git_reset(r.repo, headObj, GIT_RESET_HARD, nullptr);
    git_repository_state_cleanup(r.repo);
    if (headObj) git_object_free(headObj);
    if (rc < 0) return JRet(env, Err("abort"));
    return JRet(env, "OK");
}

// Unified patch: HEAD tree -> workdir (staged + unstaged). Empty repo => "".
extern "C" JNIEXPORT jstring JNICALL
Java_com_anvex_core_android_runtime_git_GitBridge_nativeDiff(
        JNIEnv *env, jobject /* this */, jstring path) {
    EnsureInit();
    Repo r(JStr(env, path));
    if (!r.ok()) return JRet(env, Err("open"));
    git_oid headOid;
    if (git_reference_name_to_id(&headOid, r.repo, "HEAD") < 0) return JRet(env, "");
    git_commit *head = nullptr;
    git_commit_lookup(&head, r.repo, &headOid);
    git_tree *tree = nullptr;
    git_commit_tree(&tree, head);
    git_diff_options opts = GIT_DIFF_OPTIONS_INIT;
    opts.flags = GIT_DIFF_INCLUDE_UNTRACKED | GIT_DIFF_SHOW_UNTRACKED_CONTENT;
    git_diff *diff = nullptr;
    int rc = git_diff_tree_to_workdir_with_index(&diff, r.repo, tree, &opts);
    std::string out;
    if (rc >= 0) {
        git_buf buf = {0};
        if (git_diff_to_buf(&buf, diff, GIT_DIFF_FORMAT_PATCH) == 0) {
            out.assign(buf.ptr, buf.size);
            git_buf_dispose(&buf);
        }
        git_diff_free(diff);
    }
    git_tree_free(tree);
    git_commit_free(head);
    if (rc < 0) return JRet(env, Err("diff"));
    return JRet(env, out);
}
