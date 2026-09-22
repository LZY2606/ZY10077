package com.example.migsb;

import com.example.migsb.svc.DefinitionService;
import com.example.migsb.store.ControlStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DefinitionBranchTest {

    @Test
    void conflictingEditsAreRejected_andModifiedDefCreatesBranchWithoutMovingHead() throws Exception {
        var dir = TestHarness.freshDir("branch");
        var ctx = TestHarness.start(dir);
        try {
            var store = ctx.getBean(ControlStore.class);
            var defs = ctx.getBean(DefinitionService.class);
            var mapper = ctx.getBean(ObjectMapper.class);
            ctx.getBean(com.example.migsb.svc.SampleSeeder.class).seed();
            var family = store.listFamilies().get(0);
            String head = family.headFingerprint();
            String specJson = store.findRevision(head).orElseThrow().specJson();
            var tree = mapper.readValue(specJson, com.fasterxml.jackson.databind.node.ObjectNode.class);
            tree.put("name", "浏览器 A 的改名版本");

            // 浏览器 B 先推进 head
            var bTree = mapper.readValue(specJson, com.fasterxml.jackson.databind.node.ObjectNode.class);
            bTree.put("description", "浏览器 B 先提交的修改");
            var bResult = defs.submit(mapper.writeValueAsString(bTree), family.familyId(), head, false);
            assertEquals(bResult.fingerprint(), store.findFamily(family.familyId()).orElseThrow().headFingerprint());

            // 浏览器 A 基于旧 head 提交：必须 409 冲突
            var ex = assertThrows(DefinitionService.ConflictException.class,
                    () -> defs.submit(mapper.writeValueAsString(tree), family.familyId(), head, false));
            assertEquals(head, ex.expectedHead);
            assertEquals(bResult.fingerprint(), ex.actualHead);

            // A 重新合并后可以保存为分支，不移动 head；旧运行仍绑定旧指纹
            var branch = defs.submit(mapper.writeValueAsString(tree), family.familyId(),
                    bResult.fingerprint(), true);
            assertTrue(branch.branched());
            assertEquals(bResult.fingerprint(),
                    store.findFamily(family.familyId()).orElseThrow().headFingerprint());
            assertTrue(store.findRevision(head).isPresent());
        } finally {
            TestHarness.stop(ctx);
        }
    }
}
