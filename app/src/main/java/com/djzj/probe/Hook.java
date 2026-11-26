package com.djzj.probe;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 最小编译验证版：只验证 Xposed API 能否编译通过。
 */
public class Hook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        // 空实现，仅用于验证依赖与签名
    }
}
