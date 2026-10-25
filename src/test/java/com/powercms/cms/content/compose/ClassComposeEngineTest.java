package com.powercms.cms.content.compose;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClassComposeEngineTest {
  private final ObjectMapper om = new ObjectMapper();

  @Test
  void j1689PresetStripsImitationPrefix() throws Exception {
    ObjectNode config = defaultConfig();
    Map<String, ClassComposeEngine.RoleValue> roles = new LinkedHashMap<>();
    roles.put("base", new ClassComposeEngine.RoleValue(null, "J1689", "歌曲"));
    roles.put("imitation", new ClassComposeEngine.RoleValue(null, "J16211511", "创作歌曲"));
    roles.put("world", new ClassComposeEngine.RoleValue(null, "546", "中国"));
    ClassComposeEngine.ComposeResult r = ClassComposeEngine.compose(config, roles);
    assertThat(r.classNumber()).isEqualTo("J1689 211511<546>");
  }

  @Test
  void f89AttachesWorldThenImitationWithoutSpace() throws Exception {
    ObjectNode config = defaultConfig();
    Map<String, ClassComposeEngine.RoleValue> roles = new LinkedHashMap<>();
    roles.put("base", new ClassComposeEngine.RoleValue(null, "F89", "各国概况"));
    roles.put("world", new ClassComposeEngine.RoleValue(null, "522", "英国"));
    roles.put("imitation", new ClassComposeEngine.RoleValue(null, "F24", "经济概况"));
    ClassComposeEngine.ComposeResult r = ClassComposeEngine.compose(config, roles);
    assertThat(r.classNumber()).isEqualTo("F89<522>24");
  }

  private ObjectNode defaultConfig() throws Exception {
    return (ObjectNode)
        om.readTree(
            """
            {
              "mode": "declarative",
              "order": ["base", "imitation", "special", "world", "china", "nation", "general"],
              "roles": {
                "base": { "field": "program_class" },
                "imitation": { "field": "class_imitation", "spaceBefore": true },
                "special": { "field": "class_special", "spaceBefore": true },
                "world": { "field": "class_world", "wrap": ["<", ">"] },
                "china": { "field": "class_china", "wrap": ["(", ")"] },
                "nation": { "field": "class_nation", "wrap": ["{", "}"] },
                "general": { "field": "class_general", "prefix": "-" }
              },
              "presets": [
                {
                  "whenBaseEquals": ["J1689"],
                  "order": ["base", "imitation", "special", "world", "general"],
                  "roles": { "imitation": { "stripPrefix": "J16", "spaceBefore": true } }
                },
                {
                  "whenBaseEquals": ["F89"],
                  "order": ["base", "world", "imitation", "general"],
                  "roles": { "imitation": { "stripPrefix": "F", "spaceBefore": false } }
                }
              ]
            }
            """);
  }
}
