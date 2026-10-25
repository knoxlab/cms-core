package com.powercms.cms.model.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GroovyComposeSandboxTest {
  private final ScriptSandboxService sandbox = new ScriptSandboxService(new ObjectMapper());

  @Test
  @SuppressWarnings("unchecked")
  void groovyReturnsClassNumberMap() {
    Map<String, Object> roles = new LinkedHashMap<>();
    Map<String, Object> base = new LinkedHashMap<>();
    base.put("code", "F89");
    base.put("name", "各国概况");
    roles.put("base", base);
    Map<String, Object> world = new LinkedHashMap<>();
    world.put("code", "522");
    roles.put("world", world);
    Map<String, Object> im = new LinkedHashMap<>();
    im.put("code", "F24");
    roles.put("imitation", im);

    Map<String, Object> declarative = new LinkedHashMap<>();
    declarative.put("classNumber", "F89<522>24");
    declarative.put("classCaption", "各国概况");

    Map<String, Object> bindings = new LinkedHashMap<>();
    bindings.put("roles", roles);
    bindings.put("declarative", declarative);

    String script =
        """
        def stripPrefix = { String code, String prefix ->
          if (code == null) return ''
          if (prefix && code.startsWith(prefix)) return code.substring(prefix.length())
          return code
        }
        def wrap = { String code, String left, String right ->
          return (left ?: '') + (code ?: '') + (right ?: '')
        }
        def composeDeclarative = { return declarative }
        def base = roles.base?.code
        def world = roles.world?.code
        def imitation = roles.imitation?.code
        if (base == 'F89' && world && imitation) {
          def suffix = stripPrefix(imitation, 'F')
          return [classNumber: base + wrap(world, '<', '>') + suffix, classCaption: roles.base?.name]
        }
        return composeDeclarative()
        """;

    Object raw = sandbox.evaluate(script, "GROOVY", bindings);
    assertThat(raw).isInstanceOf(Map.class);
    Map<String, Object> map = (Map<String, Object>) raw;
    assertThat(map.get("classNumber")).isEqualTo("F89<522>24");
  }
}
