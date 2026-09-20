package local.migrationlab.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CheckResult(boolean passed, String summary, Map<String, Object> detail) {
    public static CheckResult ok(String summary, Map<String, Object> detail) {
        return new CheckResult(true, summary, detail);
    }

    public static CheckResult fail(String summary, Map<String, Object> detail) {
        return new CheckResult(false, summary, detail);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>(detail);
        map.put("passed", passed);
        map.put("summary", summary);
        return map;
    }

    public static CheckResult all(List<CheckResult> results) {
        boolean passed = results.stream().allMatch(CheckResult::passed);
        String summary = passed
                ? results.size() + " 项检查全部通过"
                : results.stream().filter(item -> !item.passed).map(CheckResult::summary).findFirst().orElse("检查失败");
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("checks", results.stream().map(CheckResult::toMap).toList());
        return new CheckResult(passed, summary, detail);
    }
}
