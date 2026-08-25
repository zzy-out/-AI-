package com.legacyrecon.generate;

import freemarker.template.Configuration;
import freemarker.template.Template;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;

/**
 * 04.2 模板引擎（FreeMarker）。模板库：项目概述、模块说明、API 文档、数据字典。
 */
@Component
public class TemplateEngine {

    private final Configuration cfg;

    public TemplateEngine() throws Exception {
        this.cfg = new Configuration(Configuration.VERSION_2_3_32);
        // 不依赖文件系统模板；按字符串渲染（数据库/图谱查询结果作为数据绑定）
        cfg.setDefaultEncoding("UTF-8");
        cfg.setNumberFormat("0.##");
    }

    /** 渲染字符串模板。数据绑定的实体由调用方决定进入 entityRefs。 */
    public String render(String templateSource, Map<String, Object> data, String name) {
        try {
            Template t = new Template(name, new StringReader(templateSource), cfg);
            StringWriter sw = new StringWriter();
            t.process(data, sw);
            return sw.toString();
        } catch (Exception e) {
            throw new RuntimeException("模板渲染失败：" + name, e);
        }
    }
}