Page({
	data: {
		theme: '',
	},

	onLoad() {
		// 页面级配置验证点:本页 window 字段用页面专属 @key(detailNavBg/detailBg),
		// 与全局 window(navBgColor/bgColor)不同色;切主题时每页按自己引用的变量解析
		wx.onThemeChange((res) => {
			console.log('[darkmode-demo][detail] onThemeChange:', res)
			this.setData({ theme: res.theme })
		})

		const theme = wx.getSystemInfoSync().theme
		console.log('[darkmode-demo][detail] systemInfo.theme:', theme)
		if (theme) {
			this.setData({ theme })
		}
	},
})
