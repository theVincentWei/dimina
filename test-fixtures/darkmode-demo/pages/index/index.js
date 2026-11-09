Page({
	data: {
		theme: '',
	},

	onLoad() {
		// 系统深浅色切换回调:容器经 systemInfo patch 推送(仅 darkmode 声明包),
		// 基础库侧见 fe/packages/service/src/api/core/base/system/index.js
		wx.onThemeChange((res) => {
			console.log('[darkmode-demo] onThemeChange:', res)
			this.setData({ theme: res.theme })
		})

		const theme = wx.getSystemInfoSync().theme
		console.log('[darkmode-demo] systemInfo.theme:', theme)
		if (theme) {
			this.setData({ theme })
		}
	},
})
