package com.example.nodex.domain.service.ipfs

sealed class IPFSException(cause: Throwable) : Exception(cause) {

    class UnreachableException(cause: Throwable) : IPFSException(cause)

    class TimeoutException(cause: Throwable) : IPFSException(cause)

    class WrongCIDException(cause: Throwable) : IPFSException(cause)

    class Unknown(cause: Throwable) : IPFSException(cause)

}
