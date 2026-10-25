-- DEF-AI-01：模型能力域落库值归一。
--
-- 管理台曾按「chat / embedding / translate」取值直接落库，与规格词表（generate / embed /
-- transcribe / translate，FR-AI-LLM-004）不一致，导致按能力域路由时命中不到配置。
-- 此处把历史别名统一改写为规范值；无法识别的取值保持原样（不静默删除，便于人工排查）。
update ai_model_config set capability = 'generate'
 where lower(capability) in ('chat', 'generation', 'completion');
update ai_model_config set capability = 'embed'
 where lower(capability) in ('embedding', 'embeddings', 'vector');
update ai_model_config set capability = 'transcribe'
 where lower(capability) in ('transcription', 'asr');
update ai_model_config set capability = 'translate'
 where lower(capability) in ('translation');
update ai_model_config set capability = lower(capability)
 where capability <> lower(capability)
   and lower(capability) in ('generate', 'embed', 'transcribe', 'translate');
