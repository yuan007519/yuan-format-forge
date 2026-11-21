package com.fmtlab.toolbox.core;

/**
 * 用于判断“这段文本是不是正常的中文/日文/韩文”的字频与启发式打分。
 * 纯 Java 实现，不依赖 Android，便于在桌面 JVM 上直接跑测试。
 */
public final class CommonChars {

    private CommonChars() {}

    /** 简体中文高频字（约 400 字，覆盖日常文本 60%+ 的字频） */
    private static final String SIMPLIFIED =
            "的一是不了在人有我他这个们中来上大为和国地到以说时要就出会可也你对生能而子那得于着下自之年过发后作里用道行所然家种事成方多经么去法学如都同现当没动面起看定天分还进好小部其些主样理心她本前开但因只从想实日军者意无力它与长把机十民第公此已工使情明性知全三又关点正业外将两高间由问很最重并物手应战向头文体政美相见被利什二等产或新己制身果加西斯月话合回特代内信表化老给世位次度门任常先海通教儿原东声提立及比员解水名真论处走义各入几口认条平系气题活尔更别打女变四神总何电数安少报才结反受目太量再感建务做接必场件计管期市直德资命山金指克许统区保至队形社便空决治展马科司五基眼书非则听白却界达光放强即像难且权思王象完设式色路记南品住告类求据程北边死张该交规万取拉格望觉术领共确传师观清今切院让识候带导争运笑飞风步改收根干造言联持组每济车亲极林服快办议往元英士证近失转夫令准布始怎呢存未远叫台单影具罗字爱击流备兵连调深商算质团集百需价花党华城石级整府离况亚请技际约示复病息究线似官火断精满支视消越容照须九增研写称企八功吗包片史委乎查轻容易早曾除农找装广显吧阿李标谈吃图念六引历首医局突专费号尽另周较注语仅考落青随选列武红响虽推势参希古众构房半节土投某案黑维革划敌致陈律足态护七兴派孩验责营星够章音跟志底站严巴例防族供效续施留讲型料终答紧黄绝奇察母京段依批群项故按河米围江织害斗双境客纪采举杀攻父苏密低朝友诉止细愿千值仍男钱破网热助倒育属坐帝限船脸职速刻乐否刚威毛状率甚独球般普怕弹校苦创假久错承印晚兰试股拿脑预谁益阳若哪微尼继送急血惊伤素药适波夜省初喜卫源食险待述陆习置居劳财环排福纳欢雷警获模充负云停木游龙树疑层冷洲冲射略范竟句室异激汉村哈策演简卡罪判担州静退既衣您宗积余痛检差富灵协角占配征修皮挥胜降阶审沉坚善妈刘读啊超免压银买皇养伊怀执副乱抗犯追帮宣佛岁航优怪香著田铁控税左右份穿艺背阵草脚概恶块顿敢守酒岛托央户烈洋哥索胡款靠评版宝座释景顾弟登货互付伯慢欧换闻危忙核暗姐介坏讨丽良序升监临亮露永呼味野架域沙掉括舰鱼杂误湾吉减编楚肯测败屋跑梦散温困剑渐封救贵枪缺楼县尚毫移娘朋画班智亦耳恩短掌恐遗固席松秘谢鲁遇康虑幸均销钟诗藏赶剧票损忽巨炮旧端探湖录叶春乡附吸予礼港雨呀板庭妇归睛饭额含顺输摇招婚脱补谓督毒油疗旅泽材灭逐莫笔亡鲜词圣择寻厂睡博勒烟授诺伦岸奥唐卖俄炸载洛健堂旁宫喝借君禁阴园谋宋避抓荣姑孙逃牙束跳顶玉镇雪午练迫爷篇肉嘴馆遍凡础洞卷坦牛宁纸诸训私庄祖丝反" +
            "，。！？、；：“”‘’（）《》【】…—·";

    /** 繁體中文高频字（用于区分 Big5 与 GBK） */
    private static final String TRADITIONAL =
            "的一是不了在人有我他這個們中來上大為和國地到以說時要就出會可也你對生能而子那得於著下自之年過發後作裡用道行所然家種事成方多經麼去法學如都同現當沒動面起看定天分還進好小部其些主樣理心她本前開但因只從想實日軍者意無力它與長把機十民第公此已工使情明性知全三又關點正業外將兩高間由問很最重並物手應戰向頭文體政美相見被利什二等產或新己制身果加西斯月話合回特代內信表化老給世位次度門任常先海通教兒原東聲提立及比員解水名真論處走義各入幾口認條平系氣題活爾更別打女變四神總何電數安少報才結反受目太量再感建務做接必場件計管期市直德資命山金指克許統區保至隊形社便空決治展馬科司五基眼書非則聽白卻界達光放強即像難且權思王象完設式色路記南品住告類求據程北邊死張該交規萬取拉格望覺術領共確傳師觀清今切院讓識候帶導爭運笑飛風步改收根乾造言聯持組每濟車親極林服快辦議往元英士證近失轉夫令準布始怎呢存未遠叫台單影具羅字愛擊流備兵連調深商算質團集百需價花黨華城石級整府離況亞請技際約示復病息究線似官火斷精滿支視消越容照須九增研寫稱企八功嗎包片史委乎查輕容易早曾除農找裝廣顯吧阿李標談吃圖念六引歷首醫局突專費號盡另周較註語僅考落青隨選列武紅響雖推勢參希古眾構房半節土投某案黑維革劃敵致陳律足態護七興派孩驗責營星夠章音跟志底站嚴巴例防族供效續施留講型料終答緊黃絕奇察母京段依批群項故按河米圍江織害鬥雙境客紀採舉殺攻父蘇密低朝友訴止細願千值仍男錢破網熱助倒育屬坐帝限船臉職速刻樂否剛威毛狀率甚獨球般普怕彈校苦創假久錯承印晚蘭試股拿腦預誰益陽若哪微尼繼送急血驚傷素藥適波夜省初喜衛源食險待述陸習置居勞財環排福納歡雷警獲模充負雲停木遊龍樹疑層冷洲衝射略範竟句室異激漢村哈策演簡卡罪判擔州靜退既衣您宗積餘痛檢差富靈協角佔配徵修皮揮勝降階審沉堅善媽劉讀啊超免壓銀買皇養伊懷執副亂抗犯追幫宣佛歲航優怪香著田鐵控稅左右份穿藝背陣草腳概惡塊頓敢守酒島托央戶烈洋哥索胡款靠評版寶座釋景顧弟登貨互付伯慢歐換聞危忙核暗姐介壞討麗良序升監臨亮露永呼味野架域沙掉括艦魚雜誤灣吉減編楚肯測敗屋跑夢散溫困劍漸封救貴槍缺樓縣尚毫移娘朋畫班智亦耳恩短掌恐遺固席松秘謝魯遇康慮幸均銷鐘詩藏趕劇票損忽巨炮舊端探湖錄葉春鄉附吸予禮港雨呀板庭婦歸睛飯額含順輸搖招婚脫補謂督毒油療旅澤材滅逐莫筆亡鮮詞聖擇尋廠睡博勒煙授諾倫岸奧唐賣俄炸載洛健堂旁宮喝借君禁陰園謀宋避抓榮姑孫逃牙束跳頂玉鎮雪午練迫爺篇肉嘴館遍凡礎洞卷坦牛寧紙諸訓私莊祖絲";

    private static final java.util.HashMap<Character, Integer> COMMON = new java.util.HashMap<>(1024);

    static {
        for (int i = 0; i < SIMPLIFIED.length(); i++) {
            char c = SIMPLIFIED.charAt(i);
            if (!COMMON.containsKey(c)) COMMON.put(c, i);
        }
        for (int i = 0; i < TRADITIONAL.length(); i++) {
            char c = TRADITIONAL.charAt(i);
            if (!COMMON.containsKey(c)) COMMON.put(c, SIMPLIFIED.length() + i);
        }
    }

    /**
     * 乱码“残留”特征字符（UTF-8 被当成 latin1/1252 解码后的典型产物）。
     * 只保留正常西文里几乎不会出现的字符，避免把 café / naïve / Ørsted 这类正常文本误判成乱码。
     */
    private static final String MOJIBAKE_MARKS = "ÃÂâðÔªï¿½";

    public static boolean isCommonCjk(char c) {
        return COMMON.containsKey(c);
    }

    public static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF) || (c >= 0x20000 && c <= 0x2FA1F);
    }

    public static boolean isKana(char c) {
        return (c >= 0x3040 && c <= 0x30FF);
    }

    public static boolean isHangul(char c) {
        return (c >= 0xAC00 && c <= 0xD7AF) || (c >= 0x1100 && c <= 0x11FF);
    }

    public static boolean isAsciiLetterOrDigit(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /** 可读字符占比（控制字符、乱码替换符、私用区都算不可读） */
    public static double readableRatio(String s) {
        if (s == null || s.isEmpty()) return 0;
        int total = 0, ok = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') continue;
            total++;
            if (c == '\uFFFD') continue;
            if (Character.isISOControl(c)) continue;
            if (c >= 0xE000 && c <= 0xF8FF) continue;
            if (c >= 0xFFF0 && c <= 0xFFFF) continue;
            if (Character.isLetterOrDigit(c) || c == ' ' || isPunct(c) || isCjk(c) || isKana(c) || isHangul(c)) ok++;
        }
        return total == 0 ? 1.0 : (double) ok / total;
    }

    public static boolean isPunct(char c) {
        return (c >= 0x20 && c <= 0x7E && !isAsciiLetterOrDigit(c))
                || (c >= 0x2000 && c <= 0x206F)
                || (c >= 0x3000 && c <= 0x303F)
                || (c >= 0xFF00 && c <= 0xFF5E)
                || c == '—' || c == '…';
    }

    /** 常用汉字在全部汉字中的占比。无汉字时返回 -1（表示不适用） */
    public static double commonCjkRatio(String s) {
        int cjk = 0, common = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isCjk(c)) {
                cjk++;
                if (isCommonCjk(c)) common++;
            }
        }
        if (cjk == 0) return -1;
        return (double) common / cjk;
    }

    public static double cjkRatio(String s) {
        if (s == null || s.isEmpty()) return 0;
        int cjk = 0, total = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isISOControl(c)) continue;
            total++;
            if (isCjk(c)) cjk++;
        }
        return total == 0 ? 0 : (double) cjk / total;
    }

    public static double mojibakeMarkRatio(String s) {
        if (s == null || s.isEmpty()) return 0;
        int hits = 0, total = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') continue;
            total++;
            if (MOJIBAKE_MARKS.indexOf(c) >= 0) hits++;
        }
        return total == 0 ? 0 : (double) hits / total;
    }

    public static int countReplacement(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\uFFFD') n++;
        return n;
    }

    /**
     * 文本“正常程度”打分（0~1，越高越像正常文本）。
     * 判断依据：可读占比 + 常用汉字占比（乱码汉字通常是生僻字）+ 乱码特征字符惩罚。
     */
    public static double sanity(String s) {
        if (s == null || s.isEmpty()) return 0;
        double readable = readableRatio(s);
        double common = commonCjkRatio(s);
        double marks = mojibakeMarkRatio(s);
        int bad = countReplacement(s);
        double score = readable * 0.55;
        if (common < 0) {
            // 没有汉字：拉丁/其他语言文本，给基础分
            score += 0.45;
            // 出现大量 latin1 扩展符号则扣分
            score -= Math.min(0.45, marks * 2.2);
        } else {
            score += common * 0.45;
            // 汉字里夹杂大量 latin1 标记，几乎一定是乱码
            score -= Math.min(0.35, marks * 1.2);
        }
        score -= Math.min(0.6, bad * 3.0 / Math.max(1, s.length()));
        return Math.max(0, Math.min(1, score));
    }
}
