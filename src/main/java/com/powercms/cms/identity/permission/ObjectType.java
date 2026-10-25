package com.powercms.cms.identity.permission;

/**
 * 可授权对象类型（spec 4.6.2 / 8.6）。
 */
public final class ObjectType {

    public static final String ORG = "ORG";
    public static final String SITE = "SITE";
    public static final String FOLDER = "FOLDER";
    public static final String TAXONOMY = "TAXONOMY";
    public static final String CONTENT_TYPE = "CONTENT_TYPE";
    public static final String CONTENT = "CONTENT";
    public static final String MEDIA = "MEDIA";
    public static final String TEMPLATE = "TEMPLATE";
    public static final String CONTAINER = "CONTAINER";
    public static final String WORKFLOW = "WORKFLOW";

    public static final java.util.Set<String> ALL = java.util.Set.of(
        ORG, SITE, FOLDER, TAXONOMY, CONTENT_TYPE, CONTENT, MEDIA,
        TEMPLATE, CONTAINER, WORKFLOW);

    /** 首期实现的对象权利（spec 4.6.1）。 */
    public static final String VIEW = "view";
    public static final String EDIT = "edit";
    public static final String PUBLISH = "publish";
    public static final String MANAGE = "manage";
    public static final String ADD_CHILDREN = "add-children";
    public static final String DOWNLOAD = "download";

    public static final java.util.Set<String> RIGHTS = java.util.Set.of(
        VIEW, EDIT, PUBLISH, MANAGE, ADD_CHILDREN, DOWNLOAD);

    private ObjectType() {}
}
