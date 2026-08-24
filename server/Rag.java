package server;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RAG 检索器：从知识库表 knowledge_chunk 检索与用户问题最相关的知识块，供智能问答（DeepSeek）参考。
 *
 * 设计上分两层，方便以后升级：
 *   1. Retriever 接口 —— 可插拔。当前实现是词法检索 LexicalRetriever（字符 bigram + 关键词 + 同义词扩展），
 *      以后想换向量/语义检索，只需新增一个 Retriever 实现替换默认实现即可，chat 主流程（Api.chatJson）不感知。
 *   2. Rag 门面 —— 给上层统一入口：init() 加载知识库 / search() 检索 / buildContext() 拼参考资料段。
 *
 * 词法检索原理（中文无空格分词，用字符 bigram 做粗粒度相似度，零外部依赖）：
 *   - 归一化：转小写、全角转半角、去掉标点与空白；
 *   - 同义词扩展：命中同义词组里任一词，把整组词都并入查询（如 浇水↔灌溉、土壤湿度↔墒情↔含水量）；
 *   - 打分：查询 bigram 与「标题/关键词/正文」bigram 的重叠加权（标题权重最大、关键词次之、正文最小）；
 *   - 取 TopK；分数低于 MIN_SCORE 判为未命中 → 返回空列表，上层就不注入任何知识库上下文（保持原兜底行为）。
 */
public class Rag {

    /** 最低命中分数：低于此分视为检索不到相关知识（避免把不相关的知识块硬塞给大模型） */
    private static final double MIN_SCORE = 4.0;

    /** 默认返回最相关的 N 条 */
    private static final int DEFAULT_TOP_K = 3;

    /** 同义词组：命中任一词，整组词都会并入查询（仅扩展长度 >=2 的词，避免单字误触发） */
    /** 提问模板停用词：检索前从查询里去掉（这些词不携带检索含义，只表达"怎么问/怎么办"的意图，会污染 bigram 匹配） */
    private static final String[] QUERY_STOPWORDS = {
            "怎么办", "怎么样", "怎么", "怎样", "如何", "什么", "为啥", "为什么",
            "请问", "一下", "应该", "吗", "呢", "咋", "啥", "多久", "多少", "哪个",
            "问题", "相关", "情况", "内容"
    };

    private static final String[][] SYNONYM_GROUPS = {
            { "浇水", "灌溉", "补水", "灌水" },
            { "土壤湿度", "墒情", "含水量", "土壤水分", "水分" },
            { "太干", "干旱", "缺水", "太旱", "干透" },
            { "太湿", "过湿", "水涝", "积水", "涝" },
            { "太热", "高温", "过热", "温度高", "中暑" },
            { "太冷", "低温", "过冷", "冻害", "受冻" },
            { "告警", "警报", "报警", "预警" },
            { "阈值", "门限", "上下限", "临界值" },
            { "设置", "配置", "调整", "修改" },
            { "通风", "换气", "开窗", "透气" },
            { "遮阳", "遮光", "防晒", "遮阴" },
            { "光照", "光强", "日照", "亮度" },
            { "施肥", "肥料", "养分", "营养", "追肥" },
            { "病虫害", "虫害", "病害", "长虫", "生病", "防病" },
            { "番茄", "西红柿" },
            { "设备", "装置" },
            { "开启", "打开", "启动" },
            { "关闭", "关掉", "停止" }
    };

    /** 当前生效的检索器（词法实现；换向量检索时替换这里即可） */
    private static final Retriever retriever = new LexicalRetriever();

    private Rag() {}

    /** 启动时加载知识库（幂等：重复调用只重载一次） */
    public static synchronized void init() {
        retriever.load();
    }

    /** 检索与问题最相关的 TopK 条知识块；未命中返回空列表 */
    public static List<Chunk> search(String question, int topK) {
        if (question == null || question.trim().isEmpty()) return Collections.emptyList();
        return retriever.search(question, topK);
    }

    /** 检索命中知识块的标题（给前端「📚 参考」展示用） */
    public static List<String> searchTitles(String question, int topK) {
        List<String> titles = new ArrayList<>();
        for (Chunk c : search(question, topK)) titles.add(c.title);
        return titles;
    }

    /**
     * 把检索结果拼成给大模型的「参考资料」段落（模型应优先据此回答）。
     * 未命中时返回空串 —— 上层据此决定不追加该段，行为与原来完全一致。
     */
    public static String buildContext(String question, int topK) {
        List<Chunk> hits = search(question, topK);
        if (hits.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(
                "【知识库参考资料】（根据用户问题检索到的平台农业知识，回答时请优先参考并结合实时数据，引用时标注对应标题；"
                + "若参考资料与问题无关，忽略即可）");
        for (Chunk c : hits) {
            sb.append("\n- 标题《").append(c.title).append('》')
              .append("（分类：").append(c.category).append('）').append("：").append(c.content);
        }
        return sb.toString();
    }

    /** 一条知识库记录 */
    public static class Chunk {
        public final long id;
        public final String title;
        public final String category;
        public final String content;
        public final String keywords;
        public final String source;

        Chunk(long id, String title, String category, String content, String keywords, String source) {
            this.id = id; this.title = title; this.category = category;
            this.content = content; this.keywords = keywords; this.source = source;
        }
    }

    /** 检索器接口：可插拔（当前词法，未来可换向量） */
    public interface Retriever {
        void load();
        List<Chunk> search(String question, int topK);
    }

    /* ================= 词法检索实现 ================= */

    static final class LexicalRetriever implements Retriever {
        private volatile boolean loaded = false;
        private final List<Chunk> chunks = new ArrayList<>();
        private final List<Set<String>> titleBigrams = new ArrayList<>();
        private final List<Set<String>> kwBigrams = new ArrayList<>();
        private final List<Set<String>> contentBigrams = new ArrayList<>();
        /** bigram → IDF：在大范围知识块里出现的词越常见、权重越低（抑制"怎么/浇水"这类高频词） */
        private Map<String, Double> idf = new java.util.HashMap<>();

        @Override
        public void load() {
            synchronized (chunks) {
                chunks.clear(); titleBigrams.clear(); kwBigrams.clear(); contentBigrams.clear();
                String sql = "SELECT id, title, category, content, keywords, source FROM knowledge_chunk ORDER BY id";
                try (Connection c = DBUtil.getConnection();
                     PreparedStatement ps = c.prepareStatement(sql);
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Chunk ch = new Chunk(rs.getLong("id"), rs.getString("title"), rs.getString("category"),
                                rs.getString("content"), rs.getString("keywords"), rs.getString("source"));
                        chunks.add(ch);
                        titleBigrams.add(bigrams(norm(ch.title)));
                        kwBigrams.add(bigrams(norm(ch.keywords)));
                        contentBigrams.add(bigrams(norm(ch.content)));
                    }
                    computeIdf();
                    loaded = true;
                    System.out.println("[Rag] 知识库已加载: " + chunks.size() + " 条知识块");
                } catch (SQLException e) {
                    System.out.println("[Rag] 加载知识库失败: " + e.getMessage());
                }
            }
        }

        /** 按「标题∪关键词∪正文」统计每个 bigram 出现在多少个知识块，算平滑 IDF */
        private void computeIdf() {
            Map<String, Integer> df = new java.util.HashMap<>();
            for (int i = 0; i < chunks.size(); i++) {
                Set<String> union = new HashSet<>(titleBigrams.get(i));
                union.addAll(kwBigrams.get(i));
                union.addAll(contentBigrams.get(i));
                for (String bg : union) df.merge(bg, 1, Integer::sum);
            }
            int n = chunks.size();
            idf = new java.util.HashMap<>();
            for (Map.Entry<String, Integer> e : df.entrySet()) {
                double v = Math.log((double) (n + 1) / (e.getValue() + 1)) + 1.0;
                idf.put(e.getKey(), Math.max(1.0, v));
            }
        }

        private double idf(String bg) {
            Double v = idf.get(bg);
            return v == null ? 1.0 : v;
        }

        @Override
        public List<Chunk> search(String question, int topK) {
            if (!loaded) load();
            if (chunks.isEmpty()) return Collections.emptyList();
            String q = stripStopwords(norm(question));
            if (q.isEmpty()) return Collections.emptyList();
            String expanded = expandSynonyms(q);
            Set<String> qbg = bigrams(expanded);
            if (qbg.isEmpty()) return Collections.emptyList();

            // 逐个知识块打分
            List<double[]> scored = new ArrayList<>();
            synchronized (chunks) {
                for (int i = 0; i < chunks.size(); i++) {
                    double s = score(qbg, i);
                    if (s > 0) scored.add(new double[]{ s, i });
                }
            }
            if (scored.isEmpty()) return Collections.emptyList();
            scored.sort((a, b) -> Double.compare(b[0], a[0]));

            List<Chunk> result = new ArrayList<>();
            for (double[] pair : scored) {
                if (result.size() >= topK) break;
                if (pair[0] < MIN_SCORE) break;
                result.add(chunks.get((int) pair[1]));
            }
            return result;
        }

        /** 加权打分：每个命中 bigram 按「位置权重 × IDF」累加（标题 ×3、关键词 ×2.5、正文 ×1） */
        private double score(Set<String> qbg, int idx) {
            double s = 0;
            Set<String> title = titleBigrams.get(idx);
            Set<String> kw = kwBigrams.get(idx);
            Set<String> cont = contentBigrams.get(idx);
            for (String bg : qbg) {
                if (title.contains(bg)) s += 3 * idf(bg);
                if (kw.contains(bg))    s += 2.5 * idf(bg);
                if (cont.contains(bg))  s += 1 * idf(bg);
            }
            return s;
        }

        /** 去掉查询里的提问模板词（怎么办/如何/什么 等），避免污染 bigram 匹配 */
        static String stripStopwords(String normalizedQuery) {
            String s = normalizedQuery;
            for (String w : QUERY_STOPWORDS) {
                s = s.replace(w, "");
            }
            return s;
        }

        /** 同义词扩展：查询里命中某组任一词，就把整组词追加进查询串 */
        static String expandSynonyms(String normalizedQuery) {
            StringBuilder sb = new StringBuilder(normalizedQuery);
            for (String[] group : SYNONYM_GROUPS) {
                boolean hit = false;
                for (String term : group) {
                    if (normalizedQuery.contains(term)) { hit = true; break; }
                }
                if (hit) {
                    for (String term : group) if (!normalizedQuery.contains(term)) sb.append(term);
                }
            }
            return sb.toString();
        }

        /** 字符 bigram：len==1 退化为单个字符；len==0 返回空集 */
        static Set<String> bigrams(String s) {
            Set<String> set = new HashSet<>();
            if (s == null || s.isEmpty()) return set;
            if (s.length() == 1) { set.add(s); return set; }
            for (int i = 0; i < s.length() - 1; i++) {
                set.add(s.substring(i, i + 2));
            }
            return set;
        }

        /**
         * 归一化：去空白与标点、全角转半角、转小写，只保留中文字符/ASCII 字母/数字。
         * 中文去掉标点后仍连续成串，便于 bigram。
         */
        static String norm(String s) {
            if (s == null) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                // 全角 ASCII → 半角
                if (c >= 0xFF01 && c <= 0xFF5E) c = (char) (c - 0xFEE0);
                else if (c == 0x3000) continue; // 全角空格
                if (Character.isWhitespace(c)) continue;
                // isLetterOrDigit 已覆盖中文（CJK 属 Unicode 字母），只保留字母/数字
                if (Character.isLetterOrDigit(c)) {
                    sb.append(Character.toLowerCase(c));
                }
            }
            return sb.toString();
        }
    }
}
