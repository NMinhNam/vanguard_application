package com.fpt.vanguard.service.impl;

import com.fpt.vanguard.config.CustomPasswordDecoder;
import com.fpt.vanguard.dto.request.AuthenticationDtoRequest;
import com.fpt.vanguard.dto.request.IntrospectDtoRequest;
import com.fpt.vanguard.dto.request.LogoutDtoRequest;
import com.fpt.vanguard.dto.request.RefreshDtoRequest;
import com.fpt.vanguard.dto.response.AuthenticationDtoResponse;
import com.fpt.vanguard.dto.response.IntrospectDtoResponse;
import com.fpt.vanguard.entity.InvalidatedToken;
import com.fpt.vanguard.entity.User;
import com.fpt.vanguard.exception.AppException;
import com.fpt.vanguard.enums.ErrorCode;
import com.fpt.vanguard.mapper.mybatis.InvalidatedTokenMapper;
import com.fpt.vanguard.mapper.mybatis.UserMapper;
import com.fpt.vanguard.service.AuthenticationService;
import com.fpt.vanguard.util.DateUtil;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.text.ParseException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthenticationServiceImpl implements AuthenticationService {
    private final UserMapper userMapper;
    private final InvalidatedTokenMapper tokenMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${jwt.signer-key}")
    protected String SECRET_KEY;

    @Override
    public AuthenticationDtoResponse authenticate(AuthenticationDtoRequest request) throws JOSEException {
        var user = userMapper.findByUsername(request.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXIST));

        boolean authenticated = passwordEncoder.matches(request.getPassword(), user.getPassword());
        if (!authenticated) throw new AppException(ErrorCode.PASSWORD_INCORRECT);
        String jwtId = generateUUID();
        var accessToken = generateAccessToken(user, jwtId);
        var refreshToken = generateRefreshToken(user, jwtId);

        return AuthenticationDtoResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }

    private String generateAccessToken(User user, String UUID) throws JOSEException {
        var roleName = user.getRole().getRoleName();
        JWSHeader jwsHeader = new JWSHeader(JWSAlgorithm.HS256);

        JWTClaimsSet jwtClaimsSet = new JWTClaimsSet.Builder()
                .subject(user.getUserName())
                .jwtID(UUID)
                .issueTime(new Date())
                .expirationTime(Date.from(
                        Instant.now()
                                .plus(1, ChronoUnit.HOURS)
                        )
                )
                .claim("token_type", "access")
                .claim("scope", roleName)
                .build();

        return signToken(jwtClaimsSet, jwsHeader);
    }

    private String generateRefreshToken(User user, String UUID) throws JOSEException {
        JWSHeader jwsHeader = new JWSHeader(JWSAlgorithm.HS256);

        JWTClaimsSet jwtClaimsSet = new JWTClaimsSet.Builder()
                .subject(user.getUserName())
                .jwtID(UUID)
                .issueTime(new Date())
                .expirationTime(Date.from(
                        Instant.now()
                                .plus(30, ChronoUnit.DAYS)
                        )
                )
                .claim("token_type", "refresh")
                .build();

        return signToken(jwtClaimsSet, jwsHeader);
    }

    private String signToken(JWTClaimsSet jwtClaimsSet, JWSHeader jwsHeader) throws JOSEException {
        Payload payload = new Payload(jwtClaimsSet.toJSONObject());
        JWSObject jwsObject = new JWSObject(jwsHeader, payload);
        jwsObject.sign(new MACSigner(SECRET_KEY.getBytes()));
        return jwsObject.serialize();
    }

    @Override
    public Void logout(LogoutDtoRequest request) throws ParseException, JOSEException {
        var signedJWT = verifyToken(request.getToken());
        String jwtId = signedJWT.getJWTClaimsSet().getJWTID();
        Date expirationTime = signedJWT.getJWTClaimsSet().getExpirationTime();
        var expTime = DateUtil.convertDateToTimestamp(expirationTime);

        InvalidatedToken invalidatedToken = InvalidatedToken.builder()
                .id(jwtId)
                .expTime(expTime)
                .build();

        tokenMapper.insertInvalidatedToken(invalidatedToken);
        return null;
    }

    @Override
    public IntrospectDtoResponse introspect(IntrospectDtoRequest request) throws JOSEException, ParseException {
        verifyToken(request.getToken());
        return IntrospectDtoResponse.builder()
                .valid(true)
                .build();
    }

    @Override
    public AuthenticationDtoResponse refresh(RefreshDtoRequest request) throws ParseException, JOSEException {
        SignedJWT signedJWT = verifyToken(request.getRefreshToken());

        var tokenType = signedJWT.getJWTClaimsSet().getClaim("token_type");
        String username = signedJWT.getJWTClaimsSet().getSubject();
        if (!"refresh".equals(tokenType) || username.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_TOKEN);
        }

        var user = userMapper.findByUsername(username)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXIST));

        String jwtId = signedJWT.getJWTClaimsSet().getJWTID();
        Date expTime = signedJWT.getJWTClaimsSet().getExpirationTime();

        InvalidatedToken invalidatedToken = InvalidatedToken.builder()
                .id(jwtId)
                .expTime(DateUtil.convertDateToTimestamp(expTime))
                .build();
        tokenMapper.insertInvalidatedToken(invalidatedToken);

        String newJWTId = generateUUID();
        String newAccessToken = generateAccessToken(user, newJWTId);
        String newRefreshToken = generateRefreshToken(user, newJWTId);

        return AuthenticationDtoResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .build();
    }

    private SignedJWT verifyToken(String token) throws JOSEException, ParseException {
        SignedJWT signedJWT = SignedJWT.parse(token);
        JWSVerifier verifier = new MACVerifier(SECRET_KEY.getBytes());

        Date expiration = signedJWT.getJWTClaimsSet().getExpirationTime();
        boolean isExpired = expiration.after(new Date());
        boolean isVerified = signedJWT.verify(verifier);

        if (!(isExpired || isVerified)) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        String jwtId = signedJWT.getJWTClaimsSet().getJWTID();

        if (tokenMapper.isInvalidatedTokenExists(jwtId)) {
            throw new AppException(ErrorCode.INVALID_TOKEN);
        }

        return signedJWT;
    }

    private String generateUUID() {
        return UUID.randomUUID().toString();
    }
}
