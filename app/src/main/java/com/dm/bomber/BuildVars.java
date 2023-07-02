package com.dm.bomber;

public class BuildVars {
    public static final String SOURCECODE_URL = "https://github.com/aidgames/android-bomber";

    public static final String[] COUNTRY_CODES = {"7", "380", "375", "77", ""};

    public static final int[] COUNTRY_FLAGS = {
            R.drawable.ic_ru,
            R.drawable.ic_uk,
            R.drawable.ic_by,
            R.drawable.ic_kz,
            R.drawable.ic_all};

    public static final int[] MAX_PHONE_LENGTH = {10, 9, 9, 9, 0};

    public static final int SCHEDULED_ATTACKS_LIMIT = 10;
    public static final int MAX_REPEATS_COUNT = 10;
    public static final int REPEATS_MAX_LENGTH = String.valueOf(MAX_REPEATS_COUNT).length();
}
