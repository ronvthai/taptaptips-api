package com.taptaptips.server.service

import jakarta.mail.internet.MimeMessage
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service

@Service
class EmailService(
    private val mailSender: JavaMailSender
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Value("\${spring.mail.username}")
    private lateinit var fromEmail: String

    @Value("\${app.name:TapTapTips}")
    private lateinit var appName: String

    /**
     * Public HTTPS base used for both Android App Links and iOS Universal Links.
     * Both platforms require https:// + a verified domain; custom schemes
     * (taptaptips://) cannot be intercepted by Android App Links.
     *
     * Defaults to the production domain so we don't accidentally ship localhost
     * links if the env var is missing.
     */
    @Value("\${app.frontend.url:https://taptaptips.com}")
    private lateinit var frontendUrl: String

    /**
     * Send a password reset email.
     *
     * The reset link is a single https:// URL on our verified domain. This one
     * URL works for both platforms:
     *   - Android: caught by the App Link filter for taptaptips.com → opens app
     *   - iOS:    caught by the Universal Link `applinks:taptaptips.com` → opens app
     *   - Either platform without the app installed: falls back to the web page
     *
     * We deliberately do NOT use the taptaptips:// custom scheme here. Android
     * App Links cannot bind to custom schemes, so a taptaptips:// link in the
     * email is exactly what was breaking the Android flow. iOS already handles
     * the https:// form via its associated-domains entitlement.
     */
    fun sendPasswordResetEmail(toEmail: String, resetToken: String, displayName: String) {
        try {
            val resetLink = "$frontendUrl/reset-password?token=$resetToken"

            val message: MimeMessage = mailSender.createMimeMessage()
            val helper = MimeMessageHelper(message, true, "UTF-8")

            helper.setFrom(fromEmail, appName)
            helper.setTo(toEmail)
            helper.setSubject("$appName - Password Reset Request")

            val htmlContent = buildPasswordResetHtml(displayName, resetLink)
            helper.setText(htmlContent, true)

            mailSender.send(message)
            logger.info("✅ Password reset email sent to $toEmail")

        } catch (e: Exception) {
            logger.error("❌ Failed to send password reset email to $toEmail", e)
            throw RuntimeException("Failed to send email", e)
        }
    }

    private fun buildPasswordResetHtml(displayName: String, resetLink: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
            </head>
            <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
                <div style="background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 30px; text-align: center; border-radius: 10px 10px 0 0;">
                    <h1 style="color: white; margin: 0; font-size: 28px;">$appName</h1>
                </div>

                <div style="background: #f9f9f9; padding: 30px; border-radius: 0 0 10px 10px;">
                    <h2 style="color: #333; margin-top: 0;">Password Reset Request</h2>

                    <p>Hi $displayName,</p>

                    <p>We received a request to reset your password for your $appName account. If you didn't make this request, you can safely ignore this email.</p>

                    <p>To reset your password, click the button below:</p>

                    <div style="text-align: center; margin: 30px 0;">
                        <a href="$resetLink"
                           style="background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                                  color: white;
                                  padding: 15px 40px;
                                  text-decoration: none;
                                  border-radius: 5px;
                                  display: inline-block;
                                  font-weight: bold;
                                  box-shadow: 0 4px 6px rgba(0,0,0,0.1);">
                            Reset Password in App
                        </a>
                    </div>

                    <p style="color: #666; font-size: 14px;">
                        Or copy and paste this link into your browser:<br>
                        <a href="$resetLink" style="color: #667eea; word-break: break-all;">$resetLink</a>
                    </p>

                    <p style="color: #666; font-size: 14px; margin-top: 30px;">
                        <strong>This link will expire in 1 hour.</strong>
                    </p>

                    <p style="color: #666; font-size: 14px;">
                        If you didn't request a password reset, please ignore this email or contact support if you have concerns.
                    </p>

                    <hr style="border: none; border-top: 1px solid #ddd; margin: 30px 0;">

                    <p style="color: #999; font-size: 12px; text-align: center;">
                        © ${java.time.Year.now().value} $appName. All rights reserved.
                    </p>
                </div>
            </body>
            </html>
        """.trimIndent()
    }
}