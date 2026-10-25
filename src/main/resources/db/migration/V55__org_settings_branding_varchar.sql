-- IAM P1 修正：V54 曾将 organization_settings.branding 建为 json；
-- 实体为 varchar(2000)，统一列类型（幂等：已为 varchar(2000) 的库重复执行无害）。
«alt_settype_v55»;
