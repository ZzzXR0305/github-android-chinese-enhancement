package cn.local.repochinese;

import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.json.*;
import java.net.*;

final class PublicContent {
    static String fetch(String link)throws Exception {
        URI uri=new URI(link.trim());
        if(!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost()))throw new Exception("请输入 https://github.com/ 开头的仓库、议题或讨论链接。");
        String[] p=uri.getPath().split("/");
        if(p.length<3 || !p[1].matches("[A-Za-z0-9_.-]+") || !p[2].matches("[A-Za-z0-9_.-]+"))throw new Exception("链接需要包含所有者和仓库名。");
        String base="https://api.github.com/repos/"+p[1]+"/"+p[2];
        if(p.length>=5 && (p[3].equals("issues") || p[3].equals("pull")) && p[4].matches("[0-9]+")) {
            JSONObject issue=new JSONObject(get(base+"/issues/"+p[4],"application/vnd.github+json"));
            StringBuilder s=new StringBuilder(issue.optString("title")+"\n\n"+issue.optString("body",""));
            JSONArray comments=new JSONArray(get(base+"/issues/"+p[4]+"/comments?per_page=30","application/vnd.github+json"));
            for(int i=0;i<comments.length();i++)s.append("\n\n—— 评论 ").append(i+1).append(" ——\n").append(comments.getJSONObject(i).optString("body"));
            return s.toString();
        }
        if(p.length>=5 && p[3].equals("discussions")) {
            Document doc=Jsoup.parse(get(link,"text/html"));
            StringBuilder s=new StringBuilder(doc.title()+"\n\n");
            for(Element e:doc.select(".markdown-body"))s.append(readable(e)).append("\n\n");
            if(s.length()<doc.title().length()+8)throw new Exception("无法读取该讨论的公开正文。请在 GitHub 内用悬浮按钮翻译可见内容。");
            return s.toString();
        }
        String path=base+"/readme";
        if(p.length>5 && p[3].equals("blob")) {
            StringBuilder file=new StringBuilder();for(int i=5;i<p.length;i++){if(i>5)file.append('/');file.append(URLEncoder.encode(p[i],"UTF-8"));}
            path=base+"/contents/"+file+"?ref="+URLEncoder.encode(p[4],"UTF-8");
        }
        String html=get(path,"application/vnd.github.html+json");
        return readable(Jsoup.parse(html).body());
    }
    static String get(String link,String accept)throws Exception {
        org.jsoup.Connection.Response r=Jsoup.connect(link).userAgent("RepoChinese/1.0 (personal reading helper)")
            .header("Accept",accept).timeout(20000).maxBodySize(600000).ignoreContentType(true).ignoreHttpErrors(true).execute();
        if(r.statusCode()!=200)throw new Exception("GitHub 返回 "+r.statusCode()+"。私有仓库或受限页面请在原版 GitHub 内翻译可见内容。");
        return r.body();
    }
    static String readable(Element e) {
        e.select("script,style,img,svg").remove();
        for(Element pre:e.select("pre"))pre.text("\n```\n"+pre.text()+"\n```\n");
        for(Element c:e.select("code"))if(!c.hasParent() || !c.parent().tagName().equals("pre"))c.text("`"+c.text()+"`");
        for(Element a:e.select("a[href]")) {
            String href=a.attr("href");
            if(href.startsWith("https://") && !a.text().equals(href))a.appendText(" ("+href+")");
        }
        for(Element block:e.select("h1,h2,h3,h4,h5,h6,p,li,pre"))block.appendText("\n\n");
        return e.wholeText().trim();
    }
}
