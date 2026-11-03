// ts_bridge.cpp — F2a (P4): Tree-sitter highlighting bridge.
//
// Compiles the tree-sitter 0.25.8 core (lib.c amalgamation) plus five
// grammars (Kotlin, Java, TypeScript, TSX, Python) into libanvex-ts.so.
// The JNI surface is deliberately narrow: one parse call returns
// highlight spans + fold ranges in a compact line protocol; trees are
// cached natively (LRU, <= 8) so the Kotlin side can reparse
// incrementally by describing the single contiguous edit between the
// previous and the new text.
//
// Output protocol (all offsets are UTF-8 BYTE offsets into the source):
//   H <handle>\n
//   S <startByte> <endByte> <kind>\n   (kind: see TsSpanKind on Kotlin side)
//   F <startRow> <endRow>\n
// An empty string means "unavailable / unsupported" and the caller falls
// back to the regex highlighter.

#include <jni.h>
#include <tree_sitter/api.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <list>
#include <map>
#include <mutex>
#include <set>
#include <string>
#include <utility>
#include <vector>

extern "C" {
const TSLanguage *tree_sitter_kotlin(void);
const TSLanguage *tree_sitter_java(void);
const TSLanguage *tree_sitter_typescript(void);
const TSLanguage *tree_sitter_tsx(void);
const TSLanguage *tree_sitter_python(void);
}

namespace {

// Span kinds — mirror com.anvex.core.android.runtime.ts.TsSpanKind ids.
constexpr int KIND_KEYWORD = 1;
constexpr int KIND_STRING = 2;
constexpr int KIND_COMMENT = 3;
constexpr int KIND_NUMBER = 4;
constexpr int KIND_TYPE = 5;
constexpr int KIND_FUNCTION = 6;
constexpr int KIND_PROPERTY = 7;
constexpr int KIND_CONSTANT = 8;

constexpr size_t kMaxTrees = 8;
constexpr size_t kMaxSourceBytes = 2 * 1024 * 1024;

const TSLanguage *language_for(const std::string &name) {
    if (name == "kotlin") return tree_sitter_kotlin();
    if (name == "java") return tree_sitter_java();
    if (name == "typescript") return tree_sitter_typescript();
    if (name == "tsx") return tree_sitter_tsx();
    if (name == "python") return tree_sitter_python();
    return nullptr;
}

// Alphabetic keywords across the five grammars. Only ANONYMOUS nodes are
// matched against this set, and an identifier can never be anonymous, so
// a generous union set cannot paint ordinary names.
bool is_keyword_word(const std::string &w) {
    static const std::set<std::string> kWords = {
        "abstract", "and", "as", "assert", "async", "await", "break", "by",
        "catch", "class", "companion", "const", "constructor", "continue",
        "data", "default", "defer", "defined", "delete", "do", "dynamic",
        "else", "enum", "except", "export", "extends", "extern", "final",
        "finally", "for", "from", "fun", "global", "goto", "if",
        "implements", "import", "in", "infix", "init", "inline", "inner",
        "instanceof", "interface", "internal", "is", "it", "lateinit",
        "native", "new", "nonlocal", "object", "open", "operator", "or",
        "out", "override", "package", "pass", "private", "protected",
        "public", "raise", "reified", "return", "sealed", "set", "static",
        "strictfp", "super", "suspend", "switch", "sync", "synchronized",
        "tailrec", "this", "throw", "throws", "trait", "transient", "try",
        "type", "typealias", "typeof", "val", "var", "vararg", "virtual",
        "void", "volatile", "when", "while", "with", "yield",
        "boolean", "byte", "char", "double", "float", "int", "long",
        "short", "def", "elif", "lambda", "not",
    };
    return kWords.count(w) != 0;
}

bool contains(const std::string &s, const char *needle) {
    return s.find(needle) != std::string::npos;
}
bool ends_with(const std::string &s, const char *suffix) {
    size_t n = std::strlen(suffix);
    return s.size() >= n && s.compare(s.size() - n, n, suffix) == 0;
}

struct Span {
    uint32_t start, end;
    int kind;
};

struct Walker {
    const char *src;
    size_t src_len;
    std::vector<Span> spans;
    std::vector<std::pair<uint32_t, uint32_t>> folds;
    std::set<std::pair<uint32_t, uint32_t>> emitted;

    void emit(TSNode node, int kind) {
        uint32_t s = ts_node_start_byte(node);
        uint32_t e = ts_node_end_byte(node);
        if (s >= e) return;
        if (!emitted.insert({s, e}).second) return;
        spans.push_back({s, e, kind});
    }

    std::string text_of(TSNode node) {
        uint32_t s = ts_node_start_byte(node);
        uint32_t e = ts_node_end_byte(node);
        if (e > src_len) e = static_cast<uint32_t>(src_len);
        return std::string(src + s, src + (std::min)(e, s + 64));
    }

    // The leaf that carries the callable's name inside a call target:
    // the node itself for a plain identifier, its property/field child
    // for a member access.
    TSNode name_leaf(TSNode node) {
        TSNode prop = ts_node_child_by_field_name(node, "property", 8);
        if (!ts_node_is_null(prop)) return prop;
        TSNode field = ts_node_child_by_field_name(node, "field", 5);
        if (!ts_node_is_null(field)) return field;
        return node;
    }

    void classify_fields(TSNode node, const std::string &type) {
        // Declaration names: class-like -> TYPE, callable -> FUNCTION.
        TSNode name = ts_node_child_by_field_name(node, "name", 4);
        if (!ts_node_is_null(name)) {
            if (contains(type, "function") || contains(type, "method") ||
                contains(type, "constructor")) {
                emit(name_leaf(name), KIND_FUNCTION);
            } else if (contains(type, "class") || contains(type, "interface") ||
                       contains(type, "enum") || contains(type, "object") ||
                       contains(type, "trait") || contains(type, "annotation")) {
                emit(name, KIND_TYPE);
            }
        }
        // Call targets.
        if (contains(type, "call") || contains(type, "invocation")) {
            TSNode fn = ts_node_child_by_field_name(node, "function", 8);
            if (ts_node_is_null(fn)) fn = ts_node_child_by_field_name(node, "name", 4);
            if (!ts_node_is_null(fn)) emit(name_leaf(fn), KIND_FUNCTION);
        }
    }

    void walk(TSNode node, int depth) {
        if (depth > 1000) return;
        std::string type = ts_node_type(node);
        bool named = ts_node_is_named(node);

        if (!named) {
            // Anonymous token: a keyword when its text is a keyword word.
            if (is_keyword_word(text_of(node))) emit(node, KIND_KEYWORD);
            return;
        }
        if (type == "comment" || type == "line_comment" || type == "block_comment") {
            emit(node, KIND_COMMENT);
            return;
        }
        if (contains(type, "string") || contains(type, "character_literal") ||
            contains(type, "char_literal") || type == "text_block") {
            emit(node, KIND_STRING);
            return;  // substitutions inside strings stay string-coloured
        }
        if (type == "number" || contains(type, "integer") || contains(type, "floating") ||
            type == "float_literal" || type == "real_literal") {
            emit(node, KIND_NUMBER);
            return;
        }
        if (type == "true" || type == "false" || type == "none" || type == "null" ||
            type == "null_literal") {
            emit(node, KIND_CONSTANT);
            return;
        }
        if (type == "type_identifier") {
            emit(node, KIND_TYPE);
            return;
        }
        if (type == "property_identifier" || type == "field_identifier") {
            emit(node, KIND_PROPERTY);
            return;
        }

        classify_fields(node, type);

        // Fold ranges: multi-line bodies and blocks.
        TSPoint start = ts_node_start_point(node);
        TSPoint end = ts_node_end_point(node);
        if (end.row > start.row &&
            (type == "block" || ends_with(type, "_body") || ends_with(type, "_block") ||
             type == "declaration_list")) {
            folds.push_back({start.row, end.row});
        }

        uint32_t count = ts_node_child_count(node);
        for (uint32_t i = 0; i < count; i++) {
            walk(ts_node_child(node, i), depth + 1);
        }
    }
};

struct CacheEntry {
    TSTree *tree;
    std::string language;
};

std::mutex g_mutex;
std::map<jlong, CacheEntry> g_cache;
std::list<jlong> g_lru;  // front = most recently used
jlong g_next_handle = 1;

void cache_put(jlong handle, TSTree *tree, const std::string &language) {
    g_cache[handle] = {tree, language};
    g_lru.push_front(handle);
    while (g_cache.size() > kMaxTrees) {
        jlong victim = g_lru.back();
        g_lru.pop_back();
        auto it = g_cache.find(victim);
        if (it != g_cache.end()) {
            ts_tree_delete(it->second.tree);
            g_cache.erase(it);
        }
    }
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_anvex_core_android_runtime_ts_TsBridge_nativeTsVersion(
    JNIEnv *env, jobject /* thiz */) {
    return env->NewStringUTF("tree-sitter 0.25.8");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_anvex_core_android_runtime_ts_TsBridge_nativeParse(
    JNIEnv *env, jobject /* thiz */, jlong handle, jstring language,
    jstring source, jint edit_start, jint edit_old_end, jint edit_new_end,
    jint edit_start_row, jint edit_start_col, jint edit_old_end_row,
    jint edit_old_end_col, jint edit_new_end_row, jint edit_new_end_col) {
    const char *lang_chars = env->GetStringUTFChars(language, nullptr);
    const char *src_chars = env->GetStringUTFChars(source, nullptr);
    if (!lang_chars || !src_chars) {
        if (lang_chars) env->ReleaseStringUTFChars(language, lang_chars);
        if (src_chars) env->ReleaseStringUTFChars(source, src_chars);
        return env->NewStringUTF("");
    }
    std::string lang(lang_chars);
    std::string src(src_chars);
    env->ReleaseStringUTFChars(language, lang_chars);
    env->ReleaseStringUTFChars(source, src_chars);

    const TSLanguage *ts_lang = language_for(lang);
    if (!ts_lang || src.size() > kMaxSourceBytes) return env->NewStringUTF("");

    TSParser *parser = ts_parser_new();
    ts_parser_set_language(parser, ts_lang);

    TSTree *tree = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_cache.find(handle);
        if (handle > 0 && it != g_cache.end() && it->second.language == lang &&
            edit_start >= 0) {
            TSInputEdit edit;
            edit.start_byte = static_cast<uint32_t>(edit_start);
            edit.old_end_byte = static_cast<uint32_t>(edit_old_end);
            edit.new_end_byte = static_cast<uint32_t>(edit_new_end);
            edit.start_point = {static_cast<uint32_t>(edit_start_row),
                                static_cast<uint32_t>(edit_start_col)};
            edit.old_end_point = {static_cast<uint32_t>(edit_old_end_row),
                                  static_cast<uint32_t>(edit_old_end_col)};
            edit.new_end_point = {static_cast<uint32_t>(edit_new_end_row),
                                  static_cast<uint32_t>(edit_new_end_col)};
            ts_tree_edit(it->second.tree, &edit);
            tree = ts_parser_parse_string(parser, it->second.tree, src.data(),
                                          static_cast<uint32_t>(src.size()));
            // The edited old tree is consumed by the parse; the caller
            // owns deleting it once the new tree exists.
            ts_tree_delete(it->second.tree);
            g_cache.erase(it);
            g_lru.remove(handle);
        }
    }
    if (!tree) {
        tree = ts_parser_parse_string(parser, nullptr, src.data(),
                                      static_cast<uint32_t>(src.size()));
    }
    ts_parser_delete(parser);
    if (!tree) return env->NewStringUTF("");

    Walker walker{src.data(), src.size()};
    walker.walk(ts_tree_root_node(tree), 0);
    std::sort(walker.spans.begin(), walker.spans.end(),
              [](const Span &a, const Span &b) {
                  return a.start != b.start ? a.start < b.start : a.end < b.end;
              });
    std::sort(walker.folds.begin(), walker.folds.end());

    jlong new_handle;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        new_handle = g_next_handle++;
        cache_put(new_handle, tree, lang);
    }

    std::string out = "H " + std::to_string(new_handle) + "\n";
    char buf[64];
    for (const Span &s : walker.spans) {
        std::snprintf(buf, sizeof(buf), "S %u %u %d\n", s.start, s.end, s.kind);
        out += buf;
    }
    for (const auto &f : walker.folds) {
        std::snprintf(buf, sizeof(buf), "F %u %u\n", f.first, f.second);
        out += buf;
    }
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_anvex_core_android_runtime_ts_TsBridge_nativeRelease(
    JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_cache.find(handle);
    if (it != g_cache.end()) {
        ts_tree_delete(it->second.tree);
        g_cache.erase(it);
        g_lru.remove(handle);
    }
}
