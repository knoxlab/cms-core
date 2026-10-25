-- FR-ACCESS-001：会员档仅用于排序和展示，访问判定不比较等级数字。
ALTER TABLE sys_role
    ADD COLUMN tier_level INT;
