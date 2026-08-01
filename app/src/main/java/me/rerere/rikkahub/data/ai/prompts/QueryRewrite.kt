package me.rerere.rikkahub.data.ai.prompts

internal val DEFAULT_QUERY_REWRITE_PROMPT = """
    You are a query rewriter for a knowledge-base search engine.
    Given a recent conversation and a follow-up search query, rewrite the query into a standalone, self-contained search query that works without any surrounding context.

    Requirements:
    1. Resolve pronouns and referents ("it", "the second one", "上面那个", "第二所学校") using the conversation history so the query names the actual entities.
    2. Keep the rewritten query in the same language as the original query.
    3. Preserve the user's original intent and any numbers, names, or constraints.
    4. Output ONLY the rewritten query — a single line, no explanations, no prefixes, no quotes.
    5. If the query is already self-contained, return it unchanged.

    <conversation>
    {history}
    </conversation>

    <current_query>
    {query}
    </current_query>
""".trimIndent()

internal val DEFAULT_HYDE_PROMPT = """
    You are a hypothetical answer generator for a knowledge-base search engine.
    Given a user's query, write a short hypothetical document (2-3 sentences) that would answer the query.
    Use professional terminology and write as if the answer were already in the knowledge base.
    Output ONLY the hypothetical document — no explanations, no prefixes, no quotes.

    <query>
    {query}
    </query>
""".trimIndent()

internal val DEFAULT_MULTIQUERY_PROMPT = """
    You are a query expander for a knowledge-base search engine.
    Given a user's query, generate 2-3 alternative search queries that capture the same intent from different angles or with different wording.
    This helps improve retrieval when the original phrasing doesn't match the documents.

    Requirements:
    1. Each query should be a standalone, self-contained search query.
    2. Vary the wording, perspective, or terminology while keeping the same core intent.
    3. Keep each query in the same language as the original query.
    4. Output one query per line, no numbering, no prefixes, no explanations.
    5. Do NOT repeat the original query — only output the alternatives.

    <query>
    {query}
    </query>
""".trimIndent()
