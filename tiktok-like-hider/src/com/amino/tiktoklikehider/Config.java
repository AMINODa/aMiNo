package com.amino.tiktoklikehider;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;

/**
 * Category specs + matching rules for the aMiNo TikTok Like Hider.
 * v1.3: Arabic definite-article label variants + normalized "contains" label matching,
 * diag flag, extra tokens (sound/repost/uname).
 */
public final class Config {
    public static final String ACTION_CONFIG_UPDATE = "com.amino.tiktoklikehider.ACTION_CONFIG_UPDATE";
    public static final String CACHE_FILE = "amino_like_hider_config.json";
    public static final String PREFS_NAME = "config";
    public static final String PREF_KEY_JSON = "config_json";
    public static final String EXTRA_CONFIG_JSON = "config_json";
    public static final String KEY_DIAG = "diag";

    public static final String[] COUNT_TOKENS = {"count", "num", "number", "txt", "text", "tv", "amount"};
    private static final String[] CONTAINER_HINTS = {
        "share", "comment", "follow", "bottom", "panel", "bar", "container",
        "root", "tabs", "menu", "pager", "layout"
    };

    private static final String[] NO_TOKENS = new String[0];
    private static final String[] LIKE_POS = {"like", "likes", "liked", "digg", "diggs"};
    private static final String[] LIKE_NEG = {"dislike", "dislikes", "unlike", "unliked"};
    private static final String[] COMMENT_POS = {"comment", "comments"};
    private static final String[] SHARE_POS = {"share", "shares", "sharing", "repost"};
    private static final String[] BOOKMARK_POS = {"bookmark", "favorite", "favourite", "fav", "collect"};
    private static final String[] FOLLOW_POS = {"follow"};
    private static final String[] FOLLOW_NEG = {"following", "follower", "followers", "followed"};
    private static final String[] USERNAME_POS = {"username", "nickname", "author", "uname"};
    private static final String[] CAPTION_POS = {"caption", "desc", "description", "videodesc"};
    private static final String[] MUSIC_POS = {"music", "bgm", "song", "sound", "audio"};

    private static final String[] LBL_LIKE = {
        "like", "liked", "like video", "like this video",
        "أعجبني", "إعجاب", "أعجبني بالفيديو", "الإعجاب", "يعجبني", "لايك",
        "j'aime", "j aime", "aime", "me gusta", "curtir", "curti", "mi piace",
        "gefällt mir", "beğen", "beğendim", "нравится", "подобається", "suka",
        "menyukai", "thích", "ถูกใจ", "いいね", "いいねする", "좋아요",
        "点赞", "喜欢", "पसंद करें", "پسند کریں", "پسندیدن"
    };
    private static final String[] LBL_COMMENT = {
        "comment", "comments", "add comment", "add a comment",
        "تعليق", "تعليقات", "التعليق", "التعليقات", "أضف تعليقاً", "أضف تعليق", "اكتب تعليقاً", "اكتب تعليق",
        "commenter", "commentaire", "commentaires", "comentar", "comentario",
        "yorum yap", "komentar", "コメント", "댓글", "评论", "комментарий"
    };
    private static final String[] LBL_SHARE = {
        "share", "share video", "send", "send to friends",
        "مشاركة", "مشاركة الفيديو", "المشاركة",
        "partager", "partager la vidéo", "compartir", "teilen", "paylaş",
        "поделиться", "シェア", "共有", "공유", "分享", "condividi"
    };
    private static final String[] LBL_BOOKMARK = {
        "save", "save video", "favorite", "favorites", "add to favorites", "bookmark",
        "حفظ", "حفظ الفيديو", "المفضلة", "المفضله",
        "enregistrer", "enregistré", "favori", "favoris", "favorit", "kaydet",
        "сохранить", "お気に入り", "즐겨찾기", "收藏"
    };
    private static final String[] LBL_FOLLOW = {
        "follow", "follow account", "follow this account",
        "متابعة", "متابعة الحساب", "المتابعة", "متابعه", "تتبع",
        "s'abonner", "abonner", "suivre", "seguir", "folgen", "takip et",
        "подписаться", "フォロー", "팔로우", "关注"
    };

    public static final Spec[] CATEGORIES = {
        new Spec("like_button", "زر الإعجاب", "أيقونة القلب في شريط التفاعل بجانب الفيديو", LIKE_POS, LIKE_NEG, LBL_LIKE, true, false),
        new Spec("like_count", "عدد الإعجابات", "الرقم المعروض أسفل أيقونة الإعجاب (يُخفى مع الزر تلقائياً)", LIKE_POS, LIKE_NEG, null, false, true),
        new Spec("comment_button", "زر التعليقات", "أيقونة فقاعة التعليق", COMMENT_POS, NO_TOKENS, LBL_COMMENT, true, false),
        new Spec("comment_count", "عدد التعليقات", "الرقم أسفل أيقونة التعليق (يُخفى مع الزر تلقائياً)", COMMENT_POS, NO_TOKENS, null, false, true),
        new Spec("share_button", "زر المشاركة", "أيقونة سهم المشاركة", SHARE_POS, NO_TOKENS, LBL_SHARE, true, false),
        new Spec("share_count", "عدد المشاركات", "الرقم أسفل أيقونة المشاركة (يُخفى مع الزر تلقائياً)", SHARE_POS, NO_TOKENS, null, false, true),
        new Spec("bookmark_button", "زر الحفظ / المفضلة", "أيقونة الحفظ أو النجمة في شريط التفاعل", BOOKMARK_POS, NO_TOKENS, LBL_BOOKMARK, true, false),
        new Spec("bookmark_count", "عدد الحفظ", "الرقم أسفل أيقونة الحفظ (يُخفى مع الزر تلقائياً)", BOOKMARK_POS, NO_TOKENS, null, false, true),
        new Spec("follow_button", "زر المتابعة (+)", "زر المتابعة بجانب صورة الناشر في الخلاصة", FOLLOW_POS, FOLLOW_NEG, LBL_FOLLOW, true, false),
        new Spec("username", "اسم المستخدم", "اسم الناشر أعلى الفيديو", USERNAME_POS, NO_TOKENS, null, false, false),
        new Spec("caption", "وصف الفيديو", "النص والهاشتاغات أسفل الفيديو", CAPTION_POS, NO_TOKENS, null, false, false),
        new Spec("music", "اسم الأغنية", "شريط الموسيقى أسفل الوصف", MUSIC_POS, NO_TOKENS, null, false, false)
    };

    public static final Map<String, String> COUNT_CATEGORY_OF;
    static {
        HashMap<String, String> m = new HashMap<String, String>();
        m.put("like_button", "like_count");
        m.put("comment_button", "comment_count");
        m.put("share_button", "share_count");
        m.put("bookmark_button", "bookmark_count");
        COUNT_CATEGORY_OF = m;
    }

    public static final class Spec {
        public final boolean buttonOnly;
        public final String[] cdLabels;
        public final boolean countOnly;
        public final String desc;
        public final String key;
        public final String[] negTokens;
        public final String[] posTokens;
        public final String title;

        Spec(String key, String title, String desc, String[] posTokens, String[] negTokens,
             String[] cdLabels, boolean buttonOnly, boolean countOnly) {
            this.key = key;
            this.title = title;
            this.desc = desc;
            this.posTokens = posTokens;
            this.negTokens = negTokens;
            this.cdLabels = cdLabels;
            this.buttonOnly = buttonOnly;
            this.countOnly = countOnly;
        }
    }

    public static Map<String, Boolean> defaultMap() {
        LinkedHashMap<String, Boolean> m = new LinkedHashMap<String, Boolean>();
        for (Spec spec : CATEGORIES) {
            m.put(spec.key, Boolean.FALSE);
        }
        m.put("like_button", Boolean.TRUE);
        m.put("like_count", Boolean.TRUE);
        return m;
    }

    public static String defaultJson() {
        try {
            JSONObject o = new JSONObject();
            Map<String, Boolean> def = defaultMap();
            for (Spec spec : CATEGORIES) {
                o.put(spec.key, def.get(spec.key).booleanValue());
            }
            o.put(KEY_DIAG, true);
            return o.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    public static String[] tokenize(String s) {
        if (s == null) return new String[0];
        return s.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
    }

    /** ID / class-name matching. containerIsClass=true applies container-hint rejection. */
    public static boolean matches(Spec spec, String s, boolean containerIsClass) {
        boolean posHit = false;
        boolean countHit = false;
        for (String tok : tokenize(s)) {
            if (tok.isEmpty()) continue;
            if (containerIsClass) {
                boolean container = false;
                for (String hint : CONTAINER_HINTS) {
                    if (hint.equals(tok)) { container = true; break; }
                }
                boolean posToken = false;
                for (String p : spec.posTokens) {
                    if (p.equals(tok)) { posToken = true; break; }
                }
                if (container && !posToken) return false;
            }
            for (String neg : spec.negTokens) {
                if (neg.equals(tok)) return false;
            }
            for (String p : spec.posTokens) {
                if (p.equals(tok)) posHit = true;
            }
            for (String c : COUNT_TOKENS) {
                if (c.equals(tok)) countHit = true;
            }
        }
        if (!posHit) return false;
        if (spec.buttonOnly && countHit) return false;
        if (spec.countOnly) return countHit;
        return true;
    }

    /** Arabic-aware normalization: strip tatweel + harakat + leading definite article. */
    private static String normalizeLabel(String s) {
        if (s == null) return "";
        String x = s.trim().toLowerCase(Locale.ROOT);
        x = x.replace("\u0640", "");
        x = x.replaceAll("[\u064B-\u0652]", "");
        if (x.startsWith("ال") && x.length() > 4) x = x.substring(2);
        return x;
    }

    /**
     * v1.3: exact match OR normalized contains for entries >= 4 chars,
     * with negTokens guard (dislike/unlike never count as like).
     */
    public static boolean labelMatches(Spec spec, String label) {
        if (spec.cdLabels == null || label == null) return false;
        String s = normalizeLabel(label);
        if (s.isEmpty() || s.length() > 40) return false;
        for (String neg : spec.negTokens) {
            if (!neg.isEmpty() && s.contains(neg)) return false;
        }
        for (String want : spec.cdLabels) {
            String w = normalizeLabel(want);
            if (w.isEmpty()) continue;
            if (s.equals(w)) return true;
            if (w.length() >= 4 && s.contains(w)) return true;
        }
        return false;
    }

    private Config() {
    }
}
