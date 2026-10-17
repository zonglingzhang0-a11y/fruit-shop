import { ElMessage } from 'element-plus'

/**
 * 生成建议的结果提示：有回退、被每日调价上限拦住、没重新生成等情况时如实用警告提示，
 * 只有真的新生成了、且没有任何说明时才提示成功。两个页面（临期清单、定价建议）共用同一口径。
 */
export function notifyGenerateResult(res, strategyLabel) {
  const notes = [res?.strategyNote, res?.adjustLimitNote].filter(Boolean)
  const generated = res?.generated ?? 0
  if (notes.length) {
    ElMessage.warning({ message: `新生成 ${generated} 条。` + notes.join('；'), duration: 8000 })
  } else if (generated > 0) {
    ElMessage.success(`已生成 ${generated} 条${strategyLabel ? ' ' + strategyLabel : ''} 建议`)
  } else {
    ElMessage.info('没有需要新生成的建议（当天已生成过）')
  }
}
