package cn.local.githubcn;
public final class UpdateVersionCheck {
    public static void main(String[] args) {
        assert UpdateActivity.newer("v1.279.0-cn3", "v1.279.0-cn2");
        assert UpdateActivity.newer("v1.280.0-cn1", "v1.279.0-cn2");
        assert !UpdateActivity.newer("v1.279.0-cn1", "v1.279.0-cn2");
        assert !UpdateActivity.newer("v1.279.0-cn2", "v1.279.0-cn2");
        assert !UpdateActivity.newer("../../other", "v1.279.0-cn2");
        assert !UpdateActivity.newer("v999999999999999.0.0-cn1", "v1.279.0-cn2");
        System.out.println("Update tag comparison checks passed.");
    }
}
