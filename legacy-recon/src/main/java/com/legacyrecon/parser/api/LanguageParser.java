package com.legacyrecon.parser.api;

import com.legacyrecon.ucm.model.ParseResult;

/**
 * 02.1 LanguageParser 契约。
 * 语义：纯函数（同输入必同输出，ID 确定性）；隔离失败（单文件失败不拖垮整体）；
 * 幂等（content=null 不产出实体）。
 */
public interface LanguageParser {
    /** "java" | "c" | "cpp" */
    String language();

    ParseResult parse(ParseRequest request);
}