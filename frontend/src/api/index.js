import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

// 后端统一响应体：{ code, message, data }
//
// 两条约定（由后端确认，见 docs/01-技术方案.md 第 5 节）：
// 1) 成功：HTTP 2xx + code=0，data 是业务数据
// 2) 失败：HTTP 状态码与 body.code 一致（400 参数校验 / 404 对象不存在 / 500 服务异常）
//    所以既要处理 HTTP 层的错误，也要处理 2xx 但 code!=0 的情况。
const http = axios.create({
  baseURL: '/api',
  timeout: 20000
})

/** 把后端的错误信息包装成带 code 的 Error，方便调用方按 code 分支处理 */
function toError(code, message) {
  const err = new Error(message || '请求失败')
  err.code = code
  return err
}

/**
 * 顾客端 H5 是面向顾客的独立版式：不该出现后台风格的浮动提示
 * （实测截图里 Element Plus 的 toast 会飘在 H5 头部上面）。
 * 只有工作台页面才弹提示；H5 页面把错误展示在页面内部。
 */
function shouldToast() {
  try {
    return router.currentRoute.value?.meta?.standalone !== true
  } catch {
    return true
  }
}

function notifyError(message) {
  if (shouldToast()) {
    ElMessage.error(message)
  }
}

http.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) {
        return body.data
      }
      const err = toError(body.code, body.message)
      notifyError(err.message)
      return Promise.reject(err)
    }
    // 非统一响应体（例如静态资源、Swagger），原样返回
    return body
  },
  (error) => {
    const body = error?.response?.data
    let err
    if (body && typeof body === 'object' && 'code' in body) {
      err = toError(body.code, body.message)
    } else if (error?.response) {
      err = toError(error.response.status, `请求失败（HTTP ${error.response.status}）`)
    } else {
      err = toError(0, '网络错误：请确认后端已启动（默认 8080），或开发代理是否生效')
    }
    notifyError(err.message)
    return Promise.reject(err)
  }
)

export const api = {
  // 1 自检
  health: () => http.get('/health'),

  // 2~3 批次与临期
  createBatch: (payload) => http.post('/batches', payload),
  nearExpiry: (params) => http.get('/batches/near-expiry', { params }),
  /**
   * 顾客端特价品列表（H5 用）。
   * 契约：只返回"店主已确认价格"的批次（存在 pricing_decision），
   * finalPrice 取店主实际定的价（final_price），不是系统建议价。
   */
  onSale: (params) => http.get('/batches/on-sale', { params }),

  // 商品档案（契约外补充，用于"登记入库"选品）
  items: (params) => http.get('/items', { params }),
  createItem: (payload) => http.post('/items', payload),

  // 4~6 定价
  generateSuggestions: (payload) => http.post('/pricing/suggestions/generate', payload),
  suggestions: (params) => http.get('/pricing/suggestions', { params }),
  decide: (payload) => http.post('/pricing/decisions', payload),
  modelStatus: (params) => http.get('/pricing/model-status', { params }),

  // 7~8 客流
  trafficHourly: (params) => http.get('/traffic/hourly', { params }),
  trafficPeaks: (params) => http.get('/traffic/peaks', { params }),

  // 9~10 推送
  previewPush: (payload) => http.post('/push/preview', payload),
  sendPush: (payload) => http.post('/push/send', payload),

  // 11~12 效果与回流
  effect: (params) => http.get('/sales/effect', { params }),
  feedback: (payload) => http.post('/sales/feedback', payload)
}

export default http
