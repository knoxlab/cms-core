-- FR-AI-KB-053：非 PDF 定位符（块 / 幻灯 / 工作表锚点）
--
-- 需求：「文档引用在答案中必须展示……定位符（PDF 为页码或页码范围；
-- **其他格式为块/幻灯/工作表锚点**）」。
--
-- 核实后确认缺口不在解析侧：KnoxMedia 的 `SourceLocator` **已经产出**
-- block / slide / sheetName / rowStart / rowEnd / section，
-- 且 `doc_source_block` 表**已经存了**这些字段（`block_index` / `slide` /
-- `sheet_name` / `row_start` / `row_end` / `spine_section`）。
-- 缺的是**中间那一跳**：分块时只把 pageFrom/pageTo 带出来，
-- 于是 docx/pptx/xlsx/epub 的引用**永远拿不到定位符** ——
-- 用户看到引用却点不到位置。
--
-- **为什么不是"少写一个字段"这么简单**：这与此前几轮反复出现的
-- 「过滤侧按某字段筛、写入侧不写该字段」是同一族 —— 数据在链路上游存在、
-- 下游却拿不到，而且**完全静默**（没有报错、没有告警，
-- 只是引用里少了一个位置）。因此本列组的落地必须同时覆盖
-- 「分块抽取 → 落库 → 写索引 → 引用投影」四跳。
--
-- 全部可空：PDF 分块没有块/幻灯/行号，文本分块只有码点区间。
-- **不填默认值**：0 是合法行号，用它表示"无信息"会让"第 0 行"与"未知"无法区分。
alter table doc_chunk add column block_index integer;
alter table doc_chunk add column slide integer;
alter table doc_chunk add column sheet_name varchar(200);
alter table doc_chunk add column row_start integer;
alter table doc_chunk add column row_end integer;
alter table doc_chunk add column spine_section integer;
