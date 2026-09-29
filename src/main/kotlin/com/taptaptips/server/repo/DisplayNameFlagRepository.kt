package com.taptaptips.server.repo

import com.taptaptips.server.domain.DisplayNameFlag
import org.springframework.data.jpa.repository.JpaRepository

interface DisplayNameFlagRepository : JpaRepository<DisplayNameFlag, Long>
