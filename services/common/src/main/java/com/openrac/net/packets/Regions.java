package com.openrac.net.packets;

import java.util.Locale;

/**
 * The region codes a game server may register under. The wire carries the code as a string, and
 * clients map it to a display name. Codes are ISO 3166-1 alpha-2 country codes, then EU for a
 * server meant for all of Europe and LOCAL for a LAN or development box. A code, once listed,
 * stays, as servers and clients name it.
 */

public enum Regions {
    AD, AE, AF, AG, AI, AL, AM, AO, AQ, AR, AS, AT, AU, AW, AX, AZ,
    BA, BB, BD, BE, BF, BG, BH, BI, BJ, BL, BM, BN, BO, BQ, BR, BS, BT, BV, BW, BY, BZ,
    CA, CC, CD, CF, CG, CH, CI, CK, CL, CM, CN, CO, CR, CU, CV, CW, CX, CY, CZ,
    DE, DJ, DK, DM, DO, DZ,
    EC, EE, EG, EH, ER, ES, ET,
    FI, FJ, FK, FM, FO, FR,
    GA, GB, GD, GE, GF, GG, GH, GI, GL, GM, GN, GP, GQ, GR, GS, GT, GU, GW, GY,
    HK, HM, HN, HR, HT, HU,
    ID, IE, IL, IM, IN, IO, IQ, IR, IS, IT,
    JE, JM, JO, JP,
    KE, KG, KH, KI, KM, KN, KP, KR, KW, KY, KZ,
    LA, LB, LC, LI, LK, LR, LS, LT, LU, LV, LY,
    MA, MC, MD, ME, MF, MG, MH, MK, ML, MM, MN, MO, MP, MQ, MR, MS, MT, MU, MV, MW, MX, MY, MZ,
    NA, NC, NE, NF, NG, NI, NL, NO, NP, NR, NU, NZ,
    OM,
    PA, PE, PF, PG, PH, PK, PL, PM, PN, PR, PS, PT, PW, PY,
    QA,
    RE, RO, RS, RU, RW,
    SA, SB, SC, SD, SE, SG, SH, SI, SJ, SK, SL, SM, SN, SO, SR, SS, ST, SV, SX, SY, SZ,
    TC, TD, TF, TG, TH, TJ, TK, TL, TM, TN, TO, TR, TT, TV, TW, TZ,
    UA, UG, UM, US, UY, UZ,
    VA, VC, VE, VG, VI, VN, VU,
    WF, WS,
    YE, YT,
    ZA, ZM, ZW,
    EU, // Europe as a whole, ISO's exceptional reservation.
    LOCAL; // A LAN party or a development box.

    /**
     * Resolves a region from its code, in any case.
     * @param code The code from the environment or the wire, such as "US".
     * @return The matching region, or null for a code nobody listed.
     */

    public static Regions get(String code) {
        String upper = code.strip().toUpperCase(Locale.ROOT);

        for (Regions region : values()) {
            // The constants' names are the codes themselves.
            if (region.name().equals(upper)) return region;
        }

        return null;
    }
}
