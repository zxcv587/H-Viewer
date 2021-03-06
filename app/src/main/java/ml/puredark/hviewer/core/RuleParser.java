package ml.puredark.hviewer.core;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.util.Log;
import android.view.View;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.ReadContext;

import org.jsoup.Jsoup;
import org.jsoup.helper.StringUtil;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ml.puredark.hviewer.beans.Collection;
import ml.puredark.hviewer.beans.Comment;
import ml.puredark.hviewer.beans.Picture;
import ml.puredark.hviewer.beans.Rule;
import ml.puredark.hviewer.beans.Selector;
import ml.puredark.hviewer.beans.Tag;
import ml.puredark.hviewer.utils.MathUtil;
import ml.puredark.hviewer.utils.RegexValidateUtil;
import ml.puredark.hviewer.utils.StringEscapeUtils;

import static java.util.regex.Pattern.DOTALL;

/**
 * Created by PureDark on 2016/8/9.
 */

public class RuleParser {

    public static Map<String, String> parseUrl(String url) {
        Map<String, String> map = new HashMap<>();
        Pattern pattern = Pattern.compile("\\{([^{}]*?):([^{}]*?)\\}", DOTALL);
        Matcher matcher = pattern.matcher(url);
        while (matcher.find()) {
            map.put(matcher.group(1), matcher.group(2));
        }
        Pattern pattern2 = Pattern.compile("\\{([^{}]*?):([^{}]*?\\{[^{}]*?\\}[^{}]*?)\\}", DOTALL);
        Matcher matcher2 = pattern2.matcher(url);
        while (matcher2.find()) {
            map.put(matcher2.group(1), matcher2.group(2));
        }
        return map;
    }

    public static CharSequence getClickableHtml(Context context, String html, String sourceUrl, Html.ImageGetter imageGetter) {
        return getClickableHtml(context, html, sourceUrl, imageGetter, null);
    }

    public static CharSequence getClickableHtml(Context context, String html, String sourceUrl, Html.ImageGetter imageGetter, Html.TagHandler tagHandler) {
        Spanned spannedHtml = Html.fromHtml(html, imageGetter, tagHandler);
        SpannableStringBuilder clickableHtmlBuilder = new SpannableStringBuilder(spannedHtml);
        URLSpan[] urls = clickableHtmlBuilder.getSpans(0, spannedHtml.length(), URLSpan.class);
        for (final URLSpan span : urls) {
            setLinkClickable(context, clickableHtmlBuilder, span, sourceUrl);
        }
        return clickableHtmlBuilder;
    }

    private static void setLinkClickable(Context context, final SpannableStringBuilder clickableHtmlBuilder,
                                         final URLSpan urlSpan, String sourceUrl) {
        int start = clickableHtmlBuilder.getSpanStart(urlSpan);
        int end = clickableHtmlBuilder.getSpanEnd(urlSpan);
        int flags = clickableHtmlBuilder.getSpanFlags(urlSpan);
        ClickableSpan clickableSpan = new ClickableSpan() {
            public void onClick(View view) {
                Intent intent = new Intent();
                intent.setAction("android.intent.action.VIEW");
                String url = urlSpan.getURL();
                url = TextUtils.isEmpty(url) ? "" : RegexValidateUtil.getAbsoluteUrlFromRelative(url, sourceUrl);
                intent.setData(Uri.parse(url));
                context.startActivity(intent);
            }
        };
        clickableHtmlBuilder.setSpan(clickableSpan, start, end, flags);
    }

    public static boolean isJson(String string) {
        if (string == null)
            return false;
        string = string.trim();
        return string.startsWith("{") || string.startsWith("[");
    }

    public static List<Collection> getCollections(List<Collection> collections, String text, Rule rule, String sourceUrl) {
        try {
            if (!isJson(text)) {
                Document doc = Jsoup.parse(text);
                Elements elements = doc.select(rule.item.selector);
                for (Element element : elements) {
                    String itemStr;
                    if ("attr".equals(rule.item.fun)) {
                        itemStr = element.attr(rule.title.param);
                    } else if ("html".equals(rule.item.fun)) {
                        itemStr = element.html();
                    } else {
                        itemStr = element.toString();
                    }
                    if (rule.item.regex != null) {
                        Pattern pattern = Pattern.compile(rule.item.regex);
                        Matcher matcher = pattern.matcher(itemStr);
                        if (!matcher.find()) {
                            continue;
                        }
                    }

                    Collection collection = new Collection(collections.size() + 1);
                    collection = getCollectionDetail(collection, element, rule, sourceUrl);

                    collections.add(collection);
                }
            } else {
                ReadContext ctx = JsonPath.parse(text);
                List<ReadContext> items = ctx.read(rule.item.path);
                for (ReadContext item : items) {
                    String itemStr = item.jsonString();
                    if (rule.item.regex != null) {
                        Pattern pattern = Pattern.compile(rule.item.regex);
                        Matcher matcher = pattern.matcher(itemStr);
                        if (!matcher.find()) {
                            continue;
                        }
                    }

                    Collection collection = new Collection(collections.size() + 1);
                    collection = getCollectionDetail(collection, item, rule, sourceUrl);

                    collections.add(collection);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return collections;
    }

    public static Collection getCollectionDetail(Collection collection, String text, Rule rule, String sourceUrl) {
        try {
            if (!isJson(text)) {
                Document element = Jsoup.parse(text);
                collection = getCollectionDetail(collection, element, rule, sourceUrl);
            } else {
                ReadContext ctx = JsonPath.parse(text);
                collection = getCollectionDetail(collection, ctx, rule, sourceUrl);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return collection;
    }

    public static Collection getCollectionDetail(Collection collection, Element element, Rule rule, String sourceUrl) throws Exception {

        String idCode = parseSingleProperty(element, rule.idCode, sourceUrl, false);

        String title = parseSingleProperty(element, rule.title, sourceUrl, false);

        String uploader = parseSingleProperty(element, rule.uploader, sourceUrl, false);

        String cover = parseSingleProperty(element, rule.cover, sourceUrl, true);

        String category = parseSingleProperty(element, rule.category, sourceUrl, false);

        String datetime = parseSingleProperty(element, rule.datetime, sourceUrl, false);

        String description = parseSingleProperty(element, rule.description, sourceUrl, false);

        String ratingStr = parseSingleProperty(element, rule.rating, sourceUrl, false);

        float rating;

        if (ratingStr.matches("\\d+(.\\d+)?") && ratingStr.indexOf(".") > 0) {
            rating = Float.parseFloat(ratingStr);
        } else if (StringUtil.isNumeric(ratingStr)) {
            rating = Float.parseFloat(ratingStr);
        } else {
            String result = MathUtil.computeString(ratingStr);
            try {
                rating = result.contains("NaN") ? 0 : Float.parseFloat(result);
            } catch (NumberFormatException e) {
                rating = Math.min(ratingStr.replace(" ", "").length(), 5);
            }
        }

        Elements temp;

        List<Tag> tags = new ArrayList<>();
        if (rule.tagRule != null && rule.tagRule.item != null) {
            temp = element.select(rule.tagRule.item.selector);
            for (Element tagElement : temp) {
                String tagTitle = parseSingleProperty(tagElement, rule.tagRule.title, sourceUrl, false);
                String tagUrl = parseSingleProperty(tagElement, rule.tagRule.url, sourceUrl, true);
                if (TextUtils.isEmpty(tagUrl))
                    tagUrl = null;
                tags.add(new Tag(tags.size() + 1, tagTitle, tagUrl));
            }
        } else if (rule.tags != null) {
            List<String> tagStrs = parseSinglePropertyMatchAll(element, rule.tags, sourceUrl, false);
            for (String tagStr : tagStrs) {
                if (!TextUtils.isEmpty(tagStr))
                    tags.add(new Tag(tags.size() + 1, tagStr));
            }
        }

        List<Picture> pictures = new ArrayList<>();
        if (rule.pictureUrl != null && rule.pictureThumbnail != null) {
            if (rule.item != null) {
                temp = element.select(rule.item.selector);
                for (Element pictureElement : temp) {
                    String pictureId = parseSingleProperty(pictureElement, rule.pictureId, sourceUrl, false);
                    int pid;
                    try {
                        pid = Integer.parseInt(pictureId);
                    } catch (Exception e) {
                        pid = 0;
                    }
                    pid = (pid == 0) ? pictures.size() + 1 : pid;
                    String pictureUrl = parseSingleProperty(pictureElement, rule.pictureUrl, sourceUrl, true);
                    String PictureHighRes = parseSingleProperty(pictureElement, rule.pictureHighRes, sourceUrl, true);
                    String pictureThumbnail = parseSingleProperty(pictureElement, rule.pictureThumbnail, sourceUrl, true);
                    pictures.add(new Picture(pid, pictureUrl, pictureThumbnail, PictureHighRes, sourceUrl));
                }
            } else {
                List<String> pids = parseSinglePropertyMatchAll(element, rule.pictureId, sourceUrl, false);
                List<String> urls = parseSinglePropertyMatchAll(element, rule.pictureUrl, sourceUrl, true);
                List<String> thumbnails = parseSinglePropertyMatchAll(element, rule.pictureThumbnail, sourceUrl, true);
                List<String> highReses = parseSinglePropertyMatchAll(element, rule.pictureHighRes, sourceUrl, true);
                for (int i = 0; i < urls.size(); i++) {
                    String pictureId = (i < pids.size()) ? pids.get(i) : "";
                    int pid;
                    try {
                        pid = Integer.parseInt(pictureId);
                    } catch (Exception e) {
                        pid = 0;
                    }
                    String url = urls.get(i);
                    String thumbnail = (i < thumbnails.size()) ? thumbnails.get(i) : "";
                    String highRes = (i < highReses.size()) ? highReses.get(i) : "";
                    pictures.add(new Picture(pid, url, thumbnail, highRes, sourceUrl));
                }
            }
        }

        List<Comment> comments = new ArrayList<>();
        if (rule.commentRule != null && rule.commentRule.item != null && rule.commentRule.content != null) {
            temp = element.select(rule.commentRule.item.selector);
            for (Element commentElement : temp) {
                String commentAvatar = parseSingleProperty(commentElement, rule.commentRule.avatar, sourceUrl, false);
                String commentAuthor = parseSingleProperty(commentElement, rule.commentRule.author, sourceUrl, false);
                String commentDatetime = parseSingleProperty(commentElement, rule.commentRule.datetime, sourceUrl, false);
                String commentContent = parseSingleProperty(commentElement, rule.commentRule.content, sourceUrl, false);
                comments.add(new Comment(comments.size() + 1, commentAvatar, commentAuthor, commentDatetime, commentContent, sourceUrl));
            }
        } else if (rule.commentItem != null && rule.commentContent != null) {
            temp = element.select(rule.commentItem.selector);
            for (Element commentElement : temp) {
                String commentAvatar = parseSingleProperty(commentElement, rule.commentAvatar, sourceUrl, false);
                String commentAuthor = parseSingleProperty(commentElement, rule.commentAuthor, sourceUrl, false);
                String commentDatetime = parseSingleProperty(commentElement, rule.commentDatetime, sourceUrl, false);
                String commentContent = parseSingleProperty(commentElement, rule.commentContent, sourceUrl, false);
                comments.add(new Comment(comments.size() + 1, commentAvatar, commentAuthor, commentDatetime, commentContent, sourceUrl));
            }
        }

        if (!TextUtils.isEmpty(idCode))
            collection.idCode = idCode;
        if (!TextUtils.isEmpty(title))
            collection.title = title;
        if (!TextUtils.isEmpty(uploader))
            collection.uploader = uploader;
        if (!TextUtils.isEmpty(cover))
            collection.cover = cover;
        if (!TextUtils.isEmpty(category))
            collection.category = category;
        if (!TextUtils.isEmpty(datetime))
            collection.datetime = datetime;
        if (!TextUtils.isEmpty(description))
            collection.description = description;
        if (rating > 0)
            collection.rating = rating;
        if (!TextUtils.isEmpty(sourceUrl))
            collection.referer = sourceUrl;
        if (tags != null && tags.size() > 0)
            collection.tags = tags;
        if (pictures != null && pictures.size() > 0)
            collection.pictures = pictures;
        if (comments != null && comments.size() > 0)
            collection.comments = comments;
        return collection;
    }

    public static String parseSingleProperty(Element element, Selector selector, String sourceUrl, boolean isUrl) throws Exception {
        List<String> props = parseSinglePropertyMatchAll(element, selector, sourceUrl, isUrl);
        return (props.size() > 0) ? props.get(0) : "";
    }

    public static List<String> parseSinglePropertyMatchAll(Element element, Selector selector, String sourceUrl, boolean isUrl) throws Exception {
        List<String> props = new ArrayList<>();

        if (selector != null) {
            String prop;
            Elements temp = ("this".equals(selector.selector)) ? new Elements(element) : element.select(selector.selector);
            if (temp != null) {
                for (Element elem : temp) {
                    if ("attr".equals(selector.fun)) {
                        prop = elem.attr(selector.param);
                    } else if ("html".equals(selector.fun)) {
                        prop = elem.html();
                    } else {
                        prop = elem.toString();
                    }
                    props = getPropertyAfterRegex(props, prop, selector, sourceUrl, isUrl);
                }
            }
        }
        if (props.size() == 0)
            props.add("");
        return props;
    }

    public static String getPictureUrl(String html, Selector selector, String sourceUrl) {
        try {
            Document doc = Jsoup.parse(html);
            return parseSingleProperty(doc, selector, sourceUrl, true);
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    public static Collection getCollectionDetail(Collection collection, ReadContext jsonRoot, Rule rule, String sourceUrl) throws Exception {

        String idCode = parseSingleProperty(jsonRoot, rule.idCode, sourceUrl, false);

        String title = parseSingleProperty(jsonRoot, rule.title, sourceUrl, false);

        String uploader = parseSingleProperty(jsonRoot, rule.uploader, sourceUrl, false);

        String cover = parseSingleProperty(jsonRoot, rule.cover, sourceUrl, true);

        String category = parseSingleProperty(jsonRoot, rule.category, sourceUrl, false);

        String datetime = parseSingleProperty(jsonRoot, rule.datetime, sourceUrl, false);

        String description = parseSingleProperty(jsonRoot, rule.description, sourceUrl, false);

        String ratingStr = parseSingleProperty(jsonRoot, rule.rating, sourceUrl, false);

        float rating;

        if (ratingStr.matches("\\d+(.\\d+)?") && ratingStr.indexOf(".") > 0) {
            rating = Float.parseFloat(ratingStr);
        } else if (StringUtil.isNumeric(ratingStr)) {
            rating = Float.parseFloat(ratingStr);
        } else {
            String result = MathUtil.computeString(ratingStr);
            try {
                rating = result.contains("NaN") ? 0 : Float.parseFloat(result);
            } catch (NumberFormatException e) {
                rating = Math.min(ratingStr.replace(" ", "").length(), 5);
            }
        }

        List<ReadContext> temp;

        List<Tag> tags = new ArrayList<>();
        if (rule.tagRule != null && rule.tagRule.item != null) {
            temp = jsonRoot.read(rule.tagRule.item.path);
            for (ReadContext tagItem : temp) {
                String tagTitle = parseSingleProperty(tagItem, rule.tagRule.title, sourceUrl, false);
                String tagUrl = parseSingleProperty(tagItem, rule.tagRule.url, sourceUrl, true);
                if (TextUtils.isEmpty(tagUrl))
                    tagUrl = null;
                tags.add(new Tag(tags.size() + 1, tagTitle, tagUrl));
            }
        } else if (rule.tags != null) {
            List<String> tagStrs = parseSinglePropertyMatchAll(jsonRoot, rule.tags, sourceUrl, false);
            for (String tagStr : tagStrs) {
                if (!TextUtils.isEmpty(tagStr))
                    tags.add(new Tag(tags.size() + 1, tagStr));
            }
        }

        List<Picture> pictures = new ArrayList<>();
        if (rule.pictureUrl != null && rule.pictureThumbnail != null) {
            if (rule.item != null) {
                temp = jsonRoot.read(rule.item.path);
                for (ReadContext pictureItem : temp) {
                    String pictureId = parseSingleProperty(pictureItem, rule.pictureId, sourceUrl, false);
                    int pid;
                    try {
                        pid = Integer.parseInt(pictureId);
                    } catch (Exception e) {
                        pid = 0;
                    }

                    pid = (pid == 0) ? pictures.size() + 1 : pid;
                    String pictureUrl = parseSingleProperty(pictureItem, rule.pictureUrl, sourceUrl, true);
                    String PictureHighRes = parseSingleProperty(pictureItem, rule.pictureHighRes, sourceUrl, true);
                    String pictureThumbnail = parseSingleProperty(pictureItem, rule.pictureThumbnail, sourceUrl, true);
                    pictures.add(new Picture(pid, pictureUrl, pictureThumbnail, PictureHighRes, sourceUrl));
                }
            } else {
                List<String> pids = parseSinglePropertyMatchAll(jsonRoot, rule.pictureId, sourceUrl, false);
                List<String> urls = parseSinglePropertyMatchAll(jsonRoot, rule.pictureUrl, sourceUrl, true);
                List<String> thumbnails = parseSinglePropertyMatchAll(jsonRoot, rule.pictureThumbnail, sourceUrl, true);
                List<String> highReses = parseSinglePropertyMatchAll(jsonRoot, rule.pictureHighRes, sourceUrl, true);
                for (int i = 0; i < urls.size(); i++) {
                    String pictureId = (i < pids.size()) ? pids.get(i) : "";
                    int pid;
                    try {
                        pid = Integer.parseInt(pictureId);
                    } catch (Exception e) {
                        pid = 0;
                    }
                    String url = urls.get(i);
                    String thumbnail = (i < thumbnails.size()) ? thumbnails.get(i) : "";
                    String highRes = (i < highReses.size()) ? highReses.get(i) : "";
                    pictures.add(new Picture(pid, url, thumbnail, highRes, sourceUrl));
                }
            }
        }

        List<Comment> comments = new ArrayList<>();
        if (rule.commentRule != null && rule.commentRule.item != null && rule.commentRule.content != null) {
            temp = jsonRoot.read(rule.commentRule.item.path);
            for (ReadContext commentItem : temp) {
                String commentAvatar = parseSingleProperty(commentItem, rule.commentRule.avatar, sourceUrl, false);
                String commentAuthor = parseSingleProperty(commentItem, rule.commentRule.author, sourceUrl, false);
                String commentDatetime = parseSingleProperty(commentItem, rule.commentRule.datetime, sourceUrl, false);
                String commentContent = parseSingleProperty(commentItem, rule.commentRule.content, sourceUrl, false);
                comments.add(new Comment(comments.size() + 1, commentAvatar, commentAuthor, commentDatetime, commentContent, sourceUrl));
            }
        } else if (rule.commentItem != null && rule.commentContent != null) {
            temp = jsonRoot.read(rule.commentItem.selector);
            for (ReadContext commentItem : temp) {
                String commentAvatar = parseSingleProperty(commentItem, rule.commentAvatar, sourceUrl, false);
                String commentAuthor = parseSingleProperty(commentItem, rule.commentAuthor, sourceUrl, false);
                String commentDatetime = parseSingleProperty(commentItem, rule.commentDatetime, sourceUrl, false);
                String commentContent = parseSingleProperty(commentItem, rule.commentContent, sourceUrl, false);
                comments.add(new Comment(comments.size() + 1, commentAvatar, commentAuthor, commentDatetime, commentContent, sourceUrl));
            }
        }

        if (!TextUtils.isEmpty(idCode))
            collection.idCode = idCode;
        if (!TextUtils.isEmpty(title))
            collection.title = title;
        if (!TextUtils.isEmpty(uploader))
            collection.uploader = uploader;
        if (!TextUtils.isEmpty(cover))
            collection.cover = cover;
        if (!TextUtils.isEmpty(category))
            collection.category = category;
        if (!TextUtils.isEmpty(datetime))
            collection.datetime = datetime;
        if (!TextUtils.isEmpty(description))
            collection.description = description;
        if (rating > 0)
            collection.rating = rating;
        if (!TextUtils.isEmpty(sourceUrl))
            collection.referer = sourceUrl;
        if (tags != null && tags.size() > 0)
            collection.tags = tags;
        if (pictures != null && pictures.size() > 0)
            collection.pictures = pictures;
        if (comments != null && comments.size() > 0)
            collection.comments = comments;
        return collection;
    }


    public static String parseSingleProperty(ReadContext jsonRoot, Selector selector, String sourceUrl, boolean isUrl) throws Exception {
        List<String> props = parseSinglePropertyMatchAll(jsonRoot, selector, sourceUrl, isUrl);
        return (props.size() > 0) ? props.get(0) : "";
    }

    public static List<String> parseSinglePropertyMatchAll(ReadContext jsonRoot, Selector selector, String sourceUrl, boolean isUrl) throws Exception {
        List<String> props = new ArrayList<>();

        if (selector != null) {
            String prop;
            List<ReadContext> temp = new ArrayList<>();
            if ("this".equals(selector.path))
                temp.add(jsonRoot);
            else
                temp = jsonRoot.read(selector.path);

            if (temp != null) {
                for (ReadContext item : temp) {
                    prop = item.toString();
                    props = getPropertyAfterRegex(props, prop, selector, sourceUrl, isUrl);
                }
            }
        }
        if (props.size() == 0)
            props.add("");
        return props;
    }

    public static List<String> getPropertyAfterRegex(List<String> props, String prop, Selector selector, String sourceUrl, boolean isUrl) {
        if (selector.regex != null) {
            Pattern pattern = Pattern.compile(selector.regex, DOTALL);
            Matcher matcher = pattern.matcher(prop);
            while (matcher.find() && matcher.groupCount() >= 1) {
                if (selector.replacement != null) {
                    prop = selector.replacement;
                    for (int i = 1; i <= matcher.groupCount(); i++) {
                        String replace = matcher.group(i);
                        prop = prop.replaceAll("\\$" + i, (replace != null) ? replace : "");
                    }
                } else {
                    prop = matcher.group(1);
                }
                if (isUrl) {
                    if (TextUtils.isEmpty(prop))
                        break;
                    prop = RegexValidateUtil.getAbsoluteUrlFromRelative(prop, sourceUrl);
                }
                props.add(StringEscapeUtils.unescapeHtml(prop.trim()));
            }
        } else {
            if (isUrl) {
                if (!TextUtils.isEmpty(prop)) {
                    prop = RegexValidateUtil.getAbsoluteUrlFromRelative(prop, sourceUrl);
                    props.add(StringEscapeUtils.unescapeHtml(prop.trim()));
                }
            } else
                props.add(StringEscapeUtils.unescapeHtml(prop.trim()));
        }
        return props;
    }


}
