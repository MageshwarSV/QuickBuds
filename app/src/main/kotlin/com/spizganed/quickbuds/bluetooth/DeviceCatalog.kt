package com.spizganed.quickbuds.bluetooth

/**
 * Brands, their product series and the kind of device each series is, for Bluetooth audio of any brand. Used to
 * name a connected device ("boAt · TWS earbuds") and to pick the features that fit its kind ([Kind]).
 *
 * No list of every model ever released exists, and new ones appear every month. So a [Series] matches by its
 * series word ("airdopes", "rockerz 2"), which covers the models of that series not listed here; [Series.models]
 * are examples for the Supported devices screen. A device no rule knows still gets the standard features: its kind
 * then comes from its Bluetooth class and name words ([kindOf]).
 *
 * Brand and model names are proper nouns: not translated.
 */
object DeviceCatalog {

    enum class Kind { TWS, NECKBAND, HEADPHONES, SPEAKER }

    class Series(val name: String, val kind: Kind, val match: List<String>, val models: List<String> = emptyList())

    /**
     * [match]: words that name the brand on their own: its name, and series names no other brand uses ("airdopes",
     * "galaxy buds"). Generic series words ("flip", "elite", "earbuds") count only inside their brand ([seriesOf]).
     */
    class Brand(val name: String, val match: List<String>, val series: List<Series> = emptyList())

    private fun s(name: String, kind: Kind, match: String, vararg models: String) =
        Series(name, kind, match.split('|'), models.toList())

    private val T = Kind.TWS
    private val N = Kind.NECKBAND
    private val H = Kind.HEADPHONES
    private val S = Kind.SPEAKER

    val BRANDS: List<Brand> = listOf(
        // Full controls: the models of models.json (ModelCatalog), any of these brands' names.
        Brand("OnePlus", listOf("oneplus", "nord buds", "bullets wireless", "bulletswireless"), listOf(
            s("Bullets Wireless", N, "bullets wireless|bulletswireless", "Bullets Wireless Z2", "Bullets Wireless Z2 ANC", "Bullets Wireless Z3"),
            s("Buds", T, "nord buds|buds pro|buds z|buds ace|buds n|oneplus buds", "Nord Buds 3", "Nord Buds 3r", "Nord Buds 2r", "Buds 3", "Buds Pro 2", "Buds Z2"))),
        Brand("OPPO", listOf("oppo", "enco "), listOf(
            s("Enco M / Quiet", N, "enco m3|enco quiet", "Enco M31", "Enco M32", "Enco M33"),
            s("Enco", T, "enco", "Enco Air", "Enco Buds", "Enco Free", "Enco X", "Enco W51"))),
        Brand("realme", listOf("realme"), listOf(
            s("Buds Wireless", N, "buds wireless", "Buds Wireless 3", "Buds Wireless 3 Neo", "Buds Wireless 5 ANC"),
            s("Buds Air / T / Q", T, "buds air|buds t|buds q", "Buds Air 5", "Buds Air 6", "Buds T300", "Buds T310", "Buds Q2s"),
            s("Cobble", S, "cobble", "Pocket Bluetooth Speaker"))),
        Brand("DIZO", listOf("dizo"), listOf(s("Buds", T, "dizo buds|dizo gopods", "Buds Z", "GoPods"))),

        // India.
        Brand("boAt", listOf("boat ", "boat-", "airdopes", "rockerz", "nirvana"), listOf(
            s("Airdopes", T, "airdopes", "Airdopes 141", "Airdopes 161", "Airdopes 131", "Airdopes 171", "Airdopes 181",
                "Airdopes 311", "Airdopes 381", "Airdopes 441", "Airdopes 800", "Airdopes Atom 81", "Airdopes Alpha"),
            s("Immortal", T, "immortal", "Immortal 121", "Immortal 161"),
            s("Rockerz neckband", N, "rockerz 1|rockerz 2|rockerz 3", "Rockerz 255 Pro+", "Rockerz 235 Pro", "Rockerz 245", "Rockerz 333", "Rockerz 378"),
            s("Rockerz headphones", H, "rockerz 4|rockerz 5|rockerz 6", "Rockerz 450", "Rockerz 510", "Rockerz 518", "Rockerz 550", "Rockerz 551ANC"),
            s("Nirvana", H, "nirvana", "Nirvana 751ANC", "Nirvana Eutopia"),
            s("Stone", S, "stone ", "Stone 350", "Stone 352", "Stone 620", "Stone 1200", "Stone 1350"))),
        Brand("Noise", listOf("noise buds", "noise air", "noise flair", "noise vibe", "noise tune", "noise combat", "buds vs1", "buds combat"), listOf(
            s("Buds", T, "buds vs|noise buds|air buds|buds combat|buds x", "Buds VS102", "Buds VS104", "Buds Combat", "Air Buds Pro", "Buds X Prime"),
            s("Flair / Tune", N, "noise flair|tune active|tune charge", "Flair", "Tune Active"),
            s("Vibe", S, "noise vibe", "Vibe"))),
        Brand("Boult", listOf("boult"), listOf(
            s("Curve", N, "curve", "ProBass Curve", "Curve Max"),
            s("TWS", T, "boult z|boult y|airbass|maverick", "Z40", "Z60", "Y1", "AirBass Q10", "Maverick"),
            s("Headphones", H, "boult q|boult flex", "Q", "Flex"),
            s("BassBox", S, "bassbox", "BassBox X25"))),
        Brand("Mivi", listOf("mivi", "duopods", "superpods"), listOf(
            s("Pods", T, "duopods|superpods", "DuoPods A350", "SuperPods Halo"),
            s("Collar", N, "collar", "Collar Flash"),
            s("Speakers", S, "mivi play|mivi roam", "Play", "Roam 2"))),
        Brand("pTron", listOf("ptron", "bassbuds"), listOf(
            s("Bassbuds", T, "bassbuds|zenbuds", "Bassbuds Duo", "Bassbuds Jade", "Zenbuds"),
            s("Tangent", N, "tangent", "Tangent Lite", "Tangent Sports"),
            s("Fusion", S, "fusion", "Fusion Go"))),
        Brand("Zebronics", listOf("zebronics", "zeb-", "zeb "), listOf(
            s("Jumbo / Yoga", N, "jumbo|yoga", "Zeb-Jumbo", "Zeb Jumbo Lite", "Zeb-Yoga"),
            s("Pods", T, "zeb-pods|zeb pods|sound bomb|zeb-sound bomb", "Zeb-Sound Bomb", "Zeb-Pods"),
            s("Thunder / Duke", H, "thunder|duke", "Zeb-Thunder", "Zeb-Duke"),
            s("Speakers", S, "county|zeb-action|zeb action", "Zeb-County", "Zeb-Action"))),
        Brand("truke", listOf("truke"), listOf(s("Buds", T, "truke", "Buds Q1", "BTG Alpha"))),
        Brand("Fire-Boltt", listOf("fire-boltt", "fireboltt", "fire-pods"), listOf(s("Fire-Pods", T, "fire-pods|firepods", "Fire-Pods Ninja"))),
        Brand("Portronics", listOf("portronics"), listOf(
            s("Harmonics", N, "harmonics", "Harmonics Z2"),
            s("SoundDrum", S, "sounddrum|sound drum", "SoundDrum 1"))),
        Brand("Ambrane", listOf("ambrane"), listOf(s("Dots", T, "dots", "Dots 38"))),
        Brand("Crossbeats", listOf("crossbeats"), listOf(s("Torq", T, "torq", "Torq"))),
        Brand("GOVO", listOf("govo", "gobuds"), listOf(s("GOBUDS", T, "gobuds", "GOBUDS 600"), s("GOSURROUND", S, "gosurround", "GOSURROUND 900"))),
        Brand("Wings", listOf("wings "), listOf(s("Phantom / Flobuds", T, "phantom|flobuds", "Phantom 200", "Flobuds 200"))),
        Brand("beatXP", listOf("beatxp"), listOf(s("Soundz", T, "soundz", "Soundz"))),
        Brand("Gizmore", listOf("gizmore", "gizbuds"), listOf(s("Gizbuds", T, "gizbuds", "Gizbuds"))),
        Brand("Hammer", listOf("hammer "), listOf(s("Airflow / KO", T, "airflow|hammer ko", "Airflow", "KO"))),
        Brand("Blaupunkt", listOf("blaupunkt"), listOf(s("BTW", T, "btw", "BTW100"))),
        Brand("Lava", listOf("lava "), listOf(s("Probuds", T, "probuds", "Probuds N11"))),
        Brand("itel", listOf("itel "), listOf(s("Buds", T, "itel", "T1 Neo", "Buds Ace"))),
        Brand("Leaf", listOf("leaf "), listOf(s("Pods", T, "leaf pods", "Pods"))),
        Brand("Pebble", listOf("pebble"), listOf(s("Buds", T, "pebble", "Pebble Buds"))),
        Brand("Tagg", listOf("tagg"), listOf(s("Liberty Buds", T, "liberty buds", "Liberty Buds"))),
        Brand("Xech", listOf("xech", "speakerpods"), listOf(s("Speakerpods", T, "speakerpods", "Speakerpods 3.0"))),

        // Phone makers.
        Brand("Apple", listOf("apple", "airpods"), listOf(
            s("AirPods Max", H, "airpods max", "AirPods Max"),
            s("AirPods", T, "airpods", "AirPods", "AirPods Pro", "AirPods 4"))),
        Brand("Beats", listOf("beats"), listOf(
            s("Flex", N, "beats flex", "Beats Flex"),
            s("Studio Buds / Fit / Powerbeats", T, "studio buds|beats fit|powerbeats", "Studio Buds", "Fit Pro", "Powerbeats Pro"),
            s("Studio / Solo", H, "beats studio|beats solo", "Studio Pro", "Solo 4"),
            s("Pill", S, "beats pill", "Pill"))),
        Brand("Samsung", listOf("samsung", "galaxy buds"), listOf(s("Galaxy Buds", T, "galaxy buds", "Galaxy Buds2 Pro", "Galaxy Buds FE", "Galaxy Buds3"))),
        Brand("Google", listOf("google", "pixel buds"), listOf(s("Pixel Buds", T, "pixel buds", "Pixel Buds Pro", "Pixel Buds A-Series"))),
        Brand("Xiaomi", listOf("xiaomi", " mi true", " mi buds", " mi neckband", " mi portable"), listOf(
            s("Buds", T, "xiaomi buds| mi true| mi buds", "Xiaomi Buds 5", "Mi True Wireless Earphones 2"),
            s("Neckband", N, " mi neckband| mi sports", "Mi Neckband Pro"),
            s("Speaker", S, " mi portable|xiaomi sound| mi outdoor", "Mi Portable Bluetooth Speaker"))),
        Brand("Redmi", listOf("redmi"), listOf(
            s("Buds", T, "redmi buds|redmi earbuds|redmi airdots", "Redmi Buds 5", "Redmi Buds 5 Pro", "Redmi Earbuds 2C"),
            s("Sonic Bass", N, "sonicbass|sonic bass", "Redmi SonicBass"))),
        Brand("POCO", listOf("poco"), listOf(s("Pods", T, "poco", "POCO Pods"))),
        Brand("Motorola", listOf("motorola", "moto "), listOf(s("moto buds", T, "moto buds|moto buds+", "moto buds", "moto buds+"))),
        Brand("Nothing", listOf("nothing "), listOf(s("Ear", T, "nothing ear|ear (|ear(", "Ear (2)", "Ear (a)", "Ear (stick)"))),
        Brand("CMF", listOf("cmf "), listOf(s("Buds", T, "cmf buds", "Buds Pro 2", "Buds 2"), s("Neckband", N, "cmf neckband", "Neckband Pro"))),
        Brand("vivo", listOf("vivo", "iqoo"), listOf(s("TWS", T, "vivo tws|iqoo tws", "TWS 3", "iQOO TWS 1"))),
        Brand("Huawei", listOf("huawei", "freebuds"), listOf(s("FreeBuds", T, "freebuds", "FreeBuds Pro 3", "FreeBuds 5i"))),
        Brand("Honor", listOf("honor "), listOf(s("Earbuds / Choice", T, "honor earbuds|honor choice", "Earbuds X5", "Choice Earbuds"))),
        Brand("Infinix", listOf("infinix", "xbuds"), listOf(s("XBuds", T, "xbuds", "XBuds"))),
        Brand("TECNO", listOf("tecno"), listOf(s("Buds", T, "tecno", "Buds 3"))),
        Brand("Nokia", listOf("nokia"), listOf(s("Clarity / Go", T, "clarity|nokia go", "Clarity Earbuds", "Go Earbuds"))),
        Brand("Lenovo", listOf("lenovo", "thinkplus", "lp40"), listOf(s("LP / thinkplus", T, "lp40|lp1|lp3|thinkplus", "LP40", "LP1s"))),
        Brand("LG", listOf("lg ", "tone free"), listOf(s("Tone Free", T, "tone free|tone-free", "Tone Free T90"))),
        Brand("Meizu", listOf("meizu"), listOf(s("POP", T, "meizu pop", "POP Pro"))),
        Brand("ASUS", listOf("asus", "rog "), listOf(s("ROG Cetra", T, "cetra", "ROG Cetra True Wireless"))),
        Brand("Amazon", listOf("amazon", "echo buds"), listOf(s("Echo Buds", T, "echo buds", "Echo Buds"))),
        Brand("Microsoft", listOf("microsoft", "surface earbuds", "surface headphones"), listOf(s("Surface", T, "surface earbuds", "Surface Earbuds"), s("Surface Headphones", H, "surface headphones", "Surface Headphones 2"))),

        // Audio makers.
        Brand("Sony", listOf("sony", "wf-1000", "wh-1000", "wf-c", "wh-ch", "wi-c", "linkbuds", "srs-x", "ult field"), listOf(
            s("WF / LinkBuds", T, "wf-|linkbuds", "WF-1000XM5", "WF-1000XM4", "WF-C700N", "WF-C500", "LinkBuds S"),
            s("WI neckband", N, "wi-", "WI-C100", "WI-C200", "WI-C310"),
            s("WH headphones", H, "wh-", "WH-1000XM5", "WH-1000XM4", "WH-CH720N", "WH-CH520"),
            s("SRS / ULT speakers", S, "srs-|ult field|ult wear", "SRS-XB13", "SRS-XB100", "SRS-XE200", "ULT Field 1"))),
        Brand("JBL", listOf("jbl"), listOf(
            s("Tune / Wave / Live / Tour buds", T, "wave buds|wave beam|wave 200|tune beam|tune flex|tune buds|tune 230|live pro|live buds|live free|tour pro|endurance peak|vibe|tws",
                "Wave Buds", "Wave Beam", "Wave 200TWS", "Tune Beam", "Tune Flex", "Tune 230NC", "Tune 125TWS", "Live Pro 2", "Tour Pro 2"),
            s("Tune neckband", N, "tune 1|tune 2|tune1|tune2", "Tune 125BT", "Tune 215BT"),
            s("Tune / Live headphones", H, "tune 5|tune 6|tune 7|live 4|live 5|live 6|live 7|tour one", "Tune 510BT", "Tune 520BT", "Tune 760NC", "Live 660NC", "Tour One M2"),
            s("Speakers", S, "flip|charge|jbl go|clip|xtreme|boombox|partybox|pulse",
                "Flip 6", "Charge 5", "Go 3", "Go 4", "Clip 4", "Xtreme 3", "Boombox 3", "PartyBox 110"))),
        Brand("Bose", listOf("bose", "quietcomfort", "soundlink"), listOf(
            s("QuietComfort Earbuds", T, "earbuds", "QuietComfort Ultra Earbuds", "QuietComfort Earbuds II"),
            s("QuietComfort headphones", H, "quietcomfort|qc45|qc35|noise cancelling headphones", "QuietComfort 45", "QuietComfort Ultra Headphones"),
            s("SoundLink", S, "soundlink", "SoundLink Flex", "SoundLink Micro"))),
        Brand("Sennheiser", listOf("sennheiser", "momentum true", "momentum 4"), listOf(
            s("Momentum True Wireless / CX", T, "momentum true|true wireless|cx ", "Momentum True Wireless 4", "CX Plus"),
            s("Momentum / HD", H, "momentum 4|hd 450|hd 350|accentum", "Momentum 4 Wireless", "Accentum"))),
        Brand("Jabra", listOf("jabra"), listOf(s("Elite", T, "elite", "Elite 8 Active", "Elite 4"))),
        Brand("Marshall", listOf("marshall", "emberton", "stanmore", "willen"), listOf(
            s("Motif / Minor / Mode", T, "motif|minor|mode ii", "Motif II ANC", "Minor IV"),
            s("Major / Monitor", H, "major|monitor", "Major V", "Monitor II ANC"),
            s("Speakers", S, "emberton|stanmore|acton|willen|stockwell|middleton|woburn", "Emberton III", "Stanmore III", "Acton III", "Willen II"))),
        Brand("Ultimate Ears", listOf("ultimate ears", "ue fits", "ue boom", "wonderboom", "megaboom", "everboom"), listOf(
            s("BOOM", S, "boom", "WONDERBOOM 4", "BOOM 4", "MEGABOOM 4", "EVERBOOM"),
            s("UE Fits", T, "ue fits", "UE Fits"))),
        Brand("Anker", listOf("anker", "soundcore"), listOf(
            s("Liberty / Life / Space A", T, "liberty|life p|life a|space a|life note|r50i|p20i", "Liberty 4 NC", "Life P3", "R50i", "P20i"),
            s("Life / Space headphones", H, "life q|space q|space one", "Life Q20", "Space One"),
            s("Motion / Flare / Boom", S, "motion|flare|soundcore boom|soundcore 2|soundcore 3", "Motion 300", "Soundcore 2", "Boom 2"))),
        Brand("Skullcandy", listOf("skullcandy"), listOf(
            s("Indy / Sesh / Dime", T, "indy|sesh|dime|push", "Indy Evo", "Sesh Evo", "Dime 3"),
            s("Hesh / Crusher", H, "hesh|crusher", "Hesh ANC", "Crusher Evo"))),
        Brand("Audio-Technica", listOf("audio-technica", "ath-"), listOf(s("ATH", T, "ath-", "ATH-CKS50TW", "ATH-M50xBT2"))),
        Brand("Philips", listOf("philips"), listOf(s("TAT / TAH", T, "tat", "TAT1209", "TAT2206"), s("TAH headphones", H, "tah", "TAH4205"))),
        Brand("Bang & Olufsen", listOf("bang & olufsen", "beoplay", "beosound"), listOf(
            s("Beoplay E / EX", T, "beoplay e", "Beoplay EX", "Beoplay E8"),
            s("Beoplay H", H, "beoplay h", "Beoplay H95"),
            s("Beosound", S, "beosound", "Beosound A1"))),
        Brand("Bowers & Wilkins", listOf("bowers", "b&w"), listOf(s("Pi", T, "pi5|pi6|pi7|pi8", "Pi7 S2", "Pi8"), s("Px", H, "px7|px8", "Px7 S2", "Px8"))),
        Brand("Technics", listOf("technics", "panasonic", "eah-az"), listOf(s("EAH-AZ", T, "eah-az|rz-s|rz-b", "EAH-AZ80", "EAH-AZ60M2"))),
        Brand("Shure", listOf("shure"), listOf(s("Aonic", T, "aonic", "Aonic Free"))),
        Brand("AKG", listOf("akg "), listOf(s("N", T, "akg n", "N400"))),
        Brand("Harman Kardon", listOf("harman kardon", "harman/kardon"), listOf(s("Speakers", S, "onyx|aura|luna|go + play|go+play", "Onyx Studio 8", "Luna"))),
        Brand("Beyerdynamic", listOf("beyerdynamic"), listOf(s("Free Byrd", T, "free byrd", "Free Byrd"))),
        Brand("Cleer", listOf("cleer"), listOf(s("Ally / Roam", T, "ally|roam", "Ally Plus II"))),
        Brand("Urbanista", listOf("urbanista"), listOf(s("TWS", T, "seoul|london|paris|austin", "Seoul", "London"))),
        Brand("Sudio", listOf("sudio"), listOf(s("TWS", T, "sudio", "Ett", "A2"))),
        Brand("Libratone", listOf("libratone"), listOf(s("Track", T, "track air", "Track Air+"))),
        Brand("House of Marley", listOf("marley"), listOf(s("TWS", T, "redemption|champion|rebel", "Redemption ANC 2"))),
        Brand("Jaybird", listOf("jaybird"), listOf(s("Vista", T, "vista", "Vista 2"))),
        Brand("Poly", listOf("poly ", "plantronics"), listOf(s("Voyager", T, "voyager free", "Voyager Free 60"), s("Voyager headset", H, "voyager", "Voyager Focus 2"))),
        Brand("Edifier", listOf("edifier", "neobuds"), listOf(s("TWS / NeoBuds", T, "tws|neobuds|x3|w240", "NeoBuds Pro 2", "X3"), s("Headphones", H, "w820|wh950|w600", "W820NB"))),
        Brand("1MORE", listOf("1more", "comfobuds"), listOf(s("ComfoBuds / EVO", T, "comfobuds|evo|pistonbuds", "ComfoBuds Mini", "EVO"))),
        Brand("SoundPEATS", listOf("soundpeats"), listOf(s("TWS", T, "soundpeats", "Air3 Deluxe", "Capsule3 Pro"))),
        Brand("EarFun", listOf("earfun"), listOf(s("Air / Free", T, "earfun air|earfun free", "Air Pro 3", "Free Pro 3"))),
        Brand("QCY", listOf("qcy"), listOf(s("TWS", T, "qcy", "T13", "HT05", "MeloBuds Pro"))),
        Brand("Haylou", listOf("haylou"), listOf(s("TWS", T, "haylou", "GT1", "X1"))),
        Brand("TOZO", listOf("tozo"), listOf(s("TWS", T, "tozo", "T6", "T10", "NC9"))),
        Brand("Baseus", listOf("baseus"), listOf(s("Bowie / WM", T, "bowie|wm0|encok", "Bowie M2", "WM01"))),
        Brand("UGREEN", listOf("ugreen", "hitune"), listOf(s("HiTune", T, "hitune", "HiTune T3"))),
        Brand("Tribit", listOf("tribit", "flybuds", "stormbox"), listOf(s("FlyBuds", T, "flybuds", "FlyBuds 3"), s("StormBox", S, "stormbox|xsound", "StormBox Micro 2", "XSound Go"))),
        Brand("JLab", listOf("jlab"), listOf(s("GO Air / JBuds", T, "go air|jbuds|epic air", "GO Air Pop", "JBuds Air"))),
        Brand("Raycon", listOf("raycon"), listOf(s("Everyday", T, "raycon", "Everyday Earbuds"))),
        Brand("JOYROOM", listOf("joyroom", "jr-"), listOf(s("TWS", T, "joyroom|jr-", "JR-T03S"))),
        Brand("HOCO", listOf("hoco"), listOf(s("EW / ES", T, "hoco", "EW04"))),
        Brand("Moondrop", listOf("moondrop"), listOf(s("Space Travel", T, "space travel", "Space Travel"))),
        Brand("FiiO", listOf("fiio"), listOf(s("UTWS / FW", T, "utws|fw3|fw5", "UTWS5"))),

        // Gaming.
        Brand("Razer", listOf("razer", "hammerhead"), listOf(s("Hammerhead", T, "hammerhead", "Hammerhead Pro HyperSpeed"), s("Barracuda", H, "barracuda", "Barracuda X"))),
        Brand("Logitech", listOf("logitech", "logi "), listOf(s("G FITS", T, "g fits", "G FITS"), s("G headsets", H, "g435|g733|pro x", "G435", "G733"))),
        Brand("SteelSeries", listOf("steelseries", "arctis"), listOf(s("Arctis GameBuds", T, "gamebuds", "Arctis GameBuds"), s("Arctis", H, "arctis", "Arctis Nova 7"))),
        Brand("HyperX", listOf("hyperx"), listOf(s("Cloud MIX Buds", T, "mix buds", "Cloud MIX Buds 2"), s("Cloud", H, "cloud", "Cloud III Wireless"))),
        Brand("Corsair", listOf("corsair"), listOf(s("MP600", T, "mp600", "MP600"), s("HS / Virtuoso", H, "hs80|virtuoso", "HS80 RGB Wireless"))),
        Brand("Turtle Beach", listOf("turtle beach"), listOf(s("Scout Air", T, "scout air", "Scout Air"), s("Stealth", H, "stealth", "Stealth 600")))
    )

    /** Spaces around the name, so a word at either end matches like one in the middle. */
    private fun norm(text: String) = " " + text.lowercase() + " "

    /** The brand of [name], or of [model] (the `models.json` name) first when known. */
    fun brandOf(name: String?, model: String? = null): Brand? {
        for (text in listOfNotNull(model, name)) {
            val t = norm(text)
            BRANDS.firstOrNull { b -> b.match.any { t.contains(it) } }?.let { return it }
        }
        return null
    }

    /** The series of [name] within its brand, or null. */
    fun seriesOf(name: String?, model: String? = null): Series? {
        val brand = brandOf(name, model) ?: return null
        for (text in listOfNotNull(model, name)) {
            val t = norm(text)
            brand.series.firstOrNull { s -> s.match.any { t.contains(it) } }?.let { return it }
        }
        return null
    }

    /**
     * The kind of a device, from what is known, in order of certainty: the left / right / case parts it reports
     * ([hasCase]: a case means TWS), a loudspeaker Bluetooth class ([speakerClass]), its series, then words in its
     * name. Null when nothing tells.
     */
    fun kindOf(name: String?, model: String? = null, hasCase: Boolean = false, speakerClass: Boolean = false): Kind? {
        if (hasCase) return Kind.TWS
        if (speakerClass) return Kind.SPEAKER
        seriesOf(name, model)?.let { return it.kind }
        val t = norm(listOfNotNull(model, name).joinToString(" "))
        return when {
            // Earbud words first: "Speakerpods" are earbuds.
            listOf("buds", "pods", "tws", "earbud", "dopes", "true wireless").any { t.contains(it) } -> Kind.TWS
            listOf("neckband", "neck band", "collar", "bullets").any { t.contains(it) } -> Kind.NECKBAND
            listOf("speaker", "soundbar", "boombox", "party", "sound box", "soundbox").any { t.contains(it) } -> Kind.SPEAKER
            listOf("headphone", "headset", "over-ear", "on-ear").any { t.contains(it) } -> Kind.HEADPHONES
            else -> null
        }
    }
}
