package com.winlator.socialhub;

import java.util.ArrayList;
import java.util.List;

/** A single news article from the website news feed. */
public class NewsItem {
    public String title = "";
    public String url = "";
    public String coverImageUrl;
    public String date = "";
    public String summary = "";

    // Detail fields (filled on demand by NewsApi.fetchDetail)
    public List<String> paragraphs = new ArrayList<>();
    public List<String> bodyImageUrls = new ArrayList<>();
    public boolean detailLoaded = false;
}
