package com.taptaptips.server.repo

import com.taptaptips.server.domain.AppSetting
import org.springframework.data.jpa.repository.JpaRepository

interface AppSettingRepository : JpaRepository<AppSetting, String>
