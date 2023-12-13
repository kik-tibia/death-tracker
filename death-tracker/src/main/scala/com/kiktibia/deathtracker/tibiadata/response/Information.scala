package com.kiktibia.deathtracker.tibiadata.response

case class Api(version: Int, release: String, commit: String)

case class Status(http_code: Int)

case class Information(api: Api, status: Status)
